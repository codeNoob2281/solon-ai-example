package com.example.ai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.DocumentMutationResult;
import com.example.ai.rag.dto.KbChunkItem;
import com.example.ai.rag.dto.KbChunkListResponse;
import com.example.ai.rag.dto.KbDeleteResult;
import com.example.ai.rag.dto.KbDocumentItem;
import com.example.ai.rag.dto.KbDocumentPage;
import com.example.ai.rag.dto.KbPasteRequest;
import com.example.ai.rag.dto.KbSummary;
import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.loader.MarkdownTitleExtractor;
import com.example.ai.rag.mapper.KbChunkMapper;
import com.example.ai.rag.mapper.KbDocumentMapper;
import com.example.ai.rag.repository.MilvusChildRepository;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 知识库管理服务（知识库管理页后端编排）
 * <p>
 * 职责：汇总信息 / 文档分页列表 / 文档块预览 / 重建索引 / 删除文档 / 上传与粘贴新增。
 * 重建与删除使用<b>文档级锁</b>（按 documentId 的 ReentrantLock）串行化同一文档的
 * 变更操作：进行中（INGESTING）时其他变更请求返回 409 冲突。
 * <p>
 * 跨库语义（沿用既有取舍）：
 * <ul>
 *   <li>重建：status → INGESTING → Milvus 按 parent_id 删旧子块 → MySQL 事务内替换父块行
 *       → 子块重新向量化写入 → INGESTED；任一步失败 status → FAILED 并原样上抛；</li>
 *   <li>删除：Milvus 子块删除<b>尽力而为</b>（失败仅 WARN，不阻断）——避免 Milvus 故障期间
 *       文档无法删除；孤儿子块在检索映射时因父块行缺失被既有逻辑跳过，无召回危害。</li>
 * </ul>
 * <p>
 * 向量维度：优先读 Milvus 集合 schema（零嵌入成本）；集合未创建时回落
 * {@code EmbeddingModel.dimensions()}——该方法每次调用都发起真实嵌入请求，
 * 结果按 (apiUrl+model) 在内存缓存；嵌入服务不可用返回 null（不阻塞汇总其余字段）。
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    /** 每页条数白名单（非法值回落 10） */
    private static final List<Integer> PAGE_SIZE_WHITELIST = Arrays.asList(10, 20, 50, 100);
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 状态筛选合法值（多选；其余值忽略） */
    private static final Set<String> ALLOWED_STATUSES =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    KnowledgeBaseStore.STATUS_INGESTED,
                    KnowledgeBaseStore.STATUS_INGESTING,
                    KnowledgeBaseStore.STATUS_FAILED)));
    /** 粘贴内容允许的格式 */
    private static final Set<String> ALLOWED_FORMATS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    SourceDocument.DOC_TYPE_MARKDOWN,
                    SourceDocument.DOC_TYPE_TEXT)));
    /** 上传文件后缀白名单（与入库支持的文档格式一一对应） */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    ".md", ".markdown", ".txt", ".html", ".htm")));
    /** 上传文件大小上限（5MB） */
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    /** 粘贴内容字符上限（2MB 字符，防御性上限） */
    private static final int MAX_PASTE_CHARS = 2 * 1024 * 1024;
    /** 粘贴内容默认格式 */
    private static final String DEFAULT_PASTE_FORMAT = SourceDocument.DOC_TYPE_MARKDOWN;

    @Resource
    private KnowledgeBaseStore knowledgeBaseStore;

    @Resource
    private KbDocumentMapper documentMapper;

    @Resource
    private KbChunkMapper chunkMapper;

    @Resource
    private RagIngestionService ingestionService;

    @Resource
    private RagProperties properties;

    /**
     * Milvus 子块仓库注入点保持 {@code @Lazy}（字段注入经按类型回退解析，懒代理生效）：
     * 应用启动不依赖 Milvus 在线，首次触达（汇总/重建/删除）时才建连/建集合。
     */
    @Lazy
    @Resource
    private MilvusChildRepository milvusRepository;

    /**
     * 嵌入模型（向量维度回落路径用；Bean 构造不发起网络请求，直接注入）
     */
    @Resource(name = "ragEmbeddingModel")
    private EmbeddingModel embeddingModel;

    /** 文档级锁：documentId → lock */
    private final ConcurrentHashMap<String, ReentrantLock> documentLocks = new ConcurrentHashMap<>();

    /** 向量维度回落缓存：(apiUrl|model) → dimension（EmbeddingModel.dimensions() 每次发起真实嵌入请求） */
    private final ConcurrentHashMap<String, Integer> dimensionCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------
    // 查询

    /**
     * 汇总信息：文档数 / 父块数 / 总字符数 / 向量维度
     */
    public KbSummary summary() {
        Integer vectorDimension = resolveVectorDimension();
        return new KbSummary(
                knowledgeBaseStore.countDocuments(),
                knowledgeBaseStore.countChunks(),
                knowledgeBaseStore.sumContentChars(),
                vectorDimension);
    }

    /**
     * 向量维度解析（见类注释）：Milvus 集合 schema → 嵌入模型（缓存）→ null
     */
    private Integer resolveVectorDimension() {
        // 1. Milvus 集合 schema（集合已建时零嵌入成本；Milvus 不可用不阻塞汇总）
        try {
            java.util.OptionalInt dim = milvusRepository.getVectorDimension();
            if (dim.isPresent()) {
                return dim.getAsInt();
            }
        } catch (Exception e) {
            log.warn("[KB] 读取 Milvus 向量维度失败，尝试嵌入模型回落: {}", e.getMessage());
        }

        // 2. 嵌入模型 dimensions()（每次调用发起真实嵌入请求，按 apiUrl+model 缓存）
        RagProperties.Embedding e = properties.getEmbedding();
        String cacheKey = e.getApiUrl() + "|" + e.getModel();
        Integer cached = dimensionCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            int dimension = embeddingModel.dimensions();
            dimensionCache.put(cacheKey, dimension);
            return dimension;
        } catch (Exception ex) {
            // 3. 嵌入服务不可用：返回 null，前端显示 "-"
            log.warn("[KB] 嵌入模型向量维度获取失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 文档分页列表（搜索 + 多状态过滤 + 稳定排序 updated_at DESC, id DESC）
     *
     * @param pageNum  页码（≥1，非法回落 1）
     * @param pageSize 每页条数（白名单 10/20/50/100，非法回落 10）
     * @param title    标题模糊过滤（可空）
     * @param source   来源模糊过滤（可空）
     * @param statuses 状态多选（可空=全部；支持重复参数或逗号分隔）
     */
    public KbDocumentPage pageDocuments(int pageNum, int pageSize, String title, String source, List<String> statuses) {
        int size = PAGE_SIZE_WHITELIST.contains(pageSize) ? pageSize : DEFAULT_PAGE_SIZE;
        int num = Math.max(1, pageNum);

        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<>();
        if (hasText(title)) {
            wrapper.like(KbDocument::getTitle, title.trim());
        }
        if (hasText(source)) {
            wrapper.like(KbDocument::getSource, source.trim());
        }
        List<String> validStatuses = parseStatuses(statuses);
        if (!validStatuses.isEmpty()) {
            wrapper.in(KbDocument::getStatus, validStatuses);
        }
        wrapper.orderByDesc(KbDocument::getUpdatedAt).orderByDesc(KbDocument::getId);

        Page<KbDocument> result = documentMapper.selectPage(new Page<>(num, size), wrapper);
        List<KbDocumentItem> records = new ArrayList<>(result.getRecords().size());
        for (KbDocument doc : result.getRecords()) {
            records.add(toItem(doc));
        }
        return new KbDocumentPage(result.getTotal(), num, size, result.getPages(), records);
    }

    /**
     * 状态多选解析：重复参数与逗号分隔均支持；非法值忽略
     */
    private List<String> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String raw : statuses) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String status = part.trim();
                if (ALLOWED_STATUSES.contains(status) && !result.contains(status)) {
                    result.add(status);
                }
            }
        }
        return result;
    }

    /**
     * 实体 → 列表项（不携带原始全文 content）
     */
    private KbDocumentItem toItem(KbDocument doc) {
        KbDocumentItem item = new KbDocumentItem();
        item.setId(doc.getId());
        item.setTitle(doc.getTitle());
        item.setSource(doc.getSource());
        item.setContentType(doc.getContentType());
        item.setDocType(doc.getDocType());
        item.setContentChars(doc.getContentChars());
        item.setChunkCount(doc.getChunkCount());
        item.setStatus(doc.getStatus());
        item.setCreatedAt(doc.getCreatedAt());
        item.setUpdatedAt(doc.getUpdatedAt());
        return item;
    }

    /**
     * 文档块列表（预览）：按 chunk_index 升序全量返回
     */
    public KbChunkListResponse listChunks(String documentId) {
        if (!hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        KbDocument doc = knowledgeBaseStore.findDocumentById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }

        List<KbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId)
                .orderByAsc(KbChunk::getChunkIndex));

        List<KbChunkItem> items = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            KbChunkItem item = new KbChunkItem();
            item.setId(chunk.getId());
            item.setChunkIndex(chunk.getChunkIndex());
            item.setTitle(chunk.getTitle());
            item.setCharCount(chunk.getCharCount());
            item.setChildCount(chunk.getChildCount());
            item.setContent(chunk.getContent());
            items.add(item);
        }
        return new KbChunkListResponse(documentId, items);
    }

    // ------------------------------------------------------------
    // 变更

    /**
     * 重建索引：依据 {@code kb_document.content} 重新切分并向量化。
     * <p>
     * 文档级锁内执行：status 校验（INGESTING 则 409）→ status → INGESTING →
     * Milvus 按 parent_id 删旧子块 → {@link RagIngestionService#rebuildIndex}
     * （MySQL 父块行替换 + 子块向量化写入）→ INGESTED；失败 status → FAILED 并原样上抛。
     *
     * @param documentId 文档 id
     */
    public DocumentMutationResult reindex(String documentId) throws IOException {
        if (!hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        ReentrantLock lock = lockFor(documentId);
        lock.lock();
        try {
            KbDocument doc = knowledgeBaseStore.findDocumentById(documentId);
            if (doc == null) {
                throw new IllegalArgumentException("文档不存在: " + documentId);
            }
            if (KnowledgeBaseStore.STATUS_INGESTING.equals(doc.getStatus())) {
                throw new IllegalStateException("文档正在入库/重建中，请稍后再试");
            }
            if (doc.getContent() == null || doc.getContent().trim().isEmpty()) {
                throw new IllegalArgumentException("缺少原始内容，请删除后重新上传");
            }

            knowledgeBaseStore.markIngesting(documentId);
            try {
                // 旧父块 id → Milvus 按 parent_id 过滤删除
                List<String> oldChunkIds = knowledgeBaseStore.findChunkIdsByDocumentId(documentId);
                milvusRepository.deleteByParentIds(oldChunkIds);

                DocumentMutationResult result = ingestionService.rebuildIndex(doc);
                log.info("[KB] document reindexed: documentId={} chunks={} children={} elapsed={}ms",
                        documentId, result.getChunkCount(), result.getChildChunkCount(), result.getElapsedMs());
                return result;
            } catch (IOException | RuntimeException e) {
                knowledgeBaseStore.markFailed(documentId);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除文档：Milvus 子块删除尽力而为（失败仅 WARN，不阻断），MySQL 两表同事务删除。
     *
     * @param documentId 文档 id
     */
    public KbDeleteResult delete(String documentId) {
        if (!hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        ReentrantLock lock = lockFor(documentId);
        lock.lock();
        try {
            KbDocument doc = knowledgeBaseStore.findDocumentById(documentId);
            if (doc == null) {
                throw new IllegalArgumentException("文档不存在: " + documentId);
            }
            if (KnowledgeBaseStore.STATUS_INGESTING.equals(doc.getStatus())) {
                throw new IllegalStateException("文档正在入库/重建中，请稍后再试");
            }

            List<String> oldChunkIds = knowledgeBaseStore.findChunkIdsByDocumentId(documentId);
            try {
                milvusRepository.deleteByParentIds(oldChunkIds);
            } catch (Exception e) {
                // 尽力而为：Milvus 故障期间仍允许删除 MySQL 行；孤儿子块被检索映射的
                // 既有"父块缺失跳过"逻辑兜底，无召回危害
                log.warn("[KB] 删除 Milvus 子块失败（不阻断文档删除）: documentId={}, childChunks={}, cause={}",
                        documentId, oldChunkIds.size(), e.getMessage());
            }

            knowledgeBaseStore.deleteByDocumentId(documentId);
            log.info("[KB] document deleted: documentId={} title={} childChunks={}",
                    documentId, doc.getTitle(), oldChunkIds.size());
            return new KbDeleteResult(true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 上传文件新增（multipart）：后缀白名单 + 5MB 上限；一个文件 = 一条文档记录。
     * 同名文件不做去重拦截（示例级：允许重复上传，产生不同 id 的两条记录）。
     *
     * @param file  上传文件（.md/.markdown/.txt/.html/.htm）
     * @param title 标题覆盖（可选；缺省取文档内一级标题 → 文件名去后缀）
     */
    public DocumentMutationResult upload(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        String docType = docTypeOfFilename(originalFilename);
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("上传文件过大（限 5MB）");
        }

        String rawText = new String(file.getBytes(), StandardCharsets.UTF_8);
        SourceDocument source = new SourceDocument(null, rawText,
                resolveTitle(docType, rawText, title, stripExtension(basename(originalFilename))),
                originalFilename, originalFilename, "file", docType);
        DocumentMutationResult result = ingestionService.ingestSource(source);
        log.info("[KB] document uploaded: documentId={} filename={} chunks={} children={} elapsed={}ms",
                result.getDocumentId(), originalFilename, result.getChunkCount(),
                result.getChildChunkCount(), result.getElapsedMs());
        return result;
    }

    /**
     * 粘贴内容新增：format 限 markdown/text（默认 markdown），content 必填非空 ≤ 2MB 字符。
     * source 为 null（kb_document.source 为空，列表"来源"列显示"手动粘贴"）。
     */
    public DocumentMutationResult paste(KbPasteRequest request) {
        if (request == null || request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        String content = request.getContent();
        if (content.length() > MAX_PASTE_CHARS) {
            throw new IllegalArgumentException("内容过长（限 2MB 字符）");
        }
        String format = hasText(request.getFormat())
                ? request.getFormat().trim().toLowerCase()
                : DEFAULT_PASTE_FORMAT;
        if (!ALLOWED_FORMATS.contains(format)) {
            throw new IllegalArgumentException("format 仅支持 markdown / text");
        }

        SourceDocument source = new SourceDocument(null, content,
                resolveTitle(format, content, request.getTitle(), null),
                null, null, "text", format);
        DocumentMutationResult result;
        try {
            result = ingestionService.ingestSource(source);
        } catch (IOException e) {
            throw new RuntimeException("内容入库失败: " + e.getMessage(), e);
        }
        log.info("[KB] document pasted: documentId={} format={} chunks={} children={} elapsed={}ms",
                result.getDocumentId(), format, result.getChunkCount(),
                result.getChildChunkCount(), result.getElapsedMs());
        return result;
    }

    // ------------------------------------------------------------

    /**
     * 文档标题解析（与入库流水线同规则）：用户填写 → 文档内一级标题（按格式提取）→ fallback → 未命名文档
     */
    private String resolveTitle(String docType, String rawText, String userTitle, String fallback) {
        if (hasText(userTitle)) {
            return userTitle.trim();
        }
        if (SourceDocument.DOC_TYPE_MARKDOWN.equals(docType)) {
            String h1 = MarkdownTitleExtractor.extractMarkdownTitle(rawText);
            if (hasText(h1)) {
                return h1;
            }
        } else if (SourceDocument.DOC_TYPE_HTML.equals(docType)) {
            String h1 = MarkdownTitleExtractor.extractHtmlTitle(rawText);
            if (hasText(h1)) {
                return h1;
            }
        }
        if (hasText(fallback)) {
            return fallback.trim();
        }
        return "未命名文档";
    }

    /**
     * 按文件后缀映射文档格式（白名单外抛 400）
     */
    private String docTypeOfFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return SourceDocument.DOC_TYPE_MARKDOWN;
        }
        if (lower.endsWith(".txt")) {
            return SourceDocument.DOC_TYPE_TEXT;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return SourceDocument.DOC_TYPE_HTML;
        }
        throw new IllegalArgumentException("不支持的文件类型（仅支持 .md/.markdown/.txt/.html/.htm）: " + filename);
    }

    private String basename(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private ReentrantLock lockFor(String documentId) {
        return documentLocks.computeIfAbsent(documentId, key -> new ReentrantLock());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
