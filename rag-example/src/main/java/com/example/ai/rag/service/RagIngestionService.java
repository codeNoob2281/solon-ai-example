package com.example.ai.rag.service;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.DocumentMutationResult;
import com.example.ai.rag.dto.IngestResult;
import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.loader.MarkdownLoader;
import com.example.ai.rag.loader.MarkdownTitleExtractor;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.TextLoader;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * RAG 入库流水线（父子分段模式）
 * <p>
 * 顶层数据模型为 {@link SourceDocument}（一个文件 / 一次粘贴 / 一个 URL = 一个源文档，
 * 与 {@code kb_document} 一对一），流水线四步：
 * <ol>
 *   <li>章节切分 {@link #sectionize}：markdown 逐标题章节（{@code MarkdownLoader} 上下文继承算法，
 *       章节内容自带 "### 路径" 前缀与 title_path 元数据）；text 单章节；html 经
 *       {@code HtmlSimpleLoader} 转纯文本后单章节。</li>
 *   <li>父块切分：{@code parentSplitter}（默认 1000 token）逐章节独立切分，避免跨章节合并正文；
 *       {@code kb_chunk.chunk_index} 在文档内全局连续编号，{@code kb_chunk.title} 取章节 title_path。</li>
 *   <li>MySQL 落库：文档行（content=原始全文 / docType / status=INGESTING）+ 父块行，同一事务。</li>
 *   <li>子块切分（{@code childSplitter} 200 token）→ 批量 embedding → Milvus Upsert
 *       （子块 metadata：document_id / parent_id / child_index），成功置 INGESTED。</li>
 * </ol>
 * <p>
 * 旧实现把 {@code MarkdownLoader} 输出的每个章节当作独立文档落库（一个 md 文件产生 N 条
 * {@code kb_document} 行）；本服务自引入 {@link SourceDocument} 起"文件 → 记录"恢复一对一。
 * <p>
 * 并行处理：多源文档入库按"单线程处理最小维度 = 单个源文档"提交到可配置线程池
 * （ai.rag.ingestion.*），任务粒度天然比旧实现（章节）更粗。已知取舍：
 * MySQL↔Milvus 双写无跨库原子性（失败文档保留 status=FAILED）；
 * 同一来源重复入库会以新 id 累积（按文档删除由知识库管理接口提供）。
 */
@Slf4j
@Service
public class RagIngestionService {

    /**
     * 子块 metadata：所属文档 id
     * <p>
     * 以下三个键为「入库 ↔ 检索」的跨组件契约：入库时写入子块 metadata，
     * 检索策略（如 {@code ParentChildRetrievalStrategy}）按其读取映射，改键需两侧同步。
     */
    public static final String META_DOCUMENT_ID = "document_id";
    /**
     * 子块 metadata：父块 id（MilvusChildRepository 写入时提升为集合独立字段；
     * 检索侧「子块映射父块」依赖该键）
     */
    public static final String META_PARENT_ID = "parent_id";
    /**
     * 子块 metadata：在父块内的序号
     */
    public static final String META_CHILD_INDEX = "child_index";

    /** 标题兜底值（粘贴 / URL 无任何可解析标题时） */
    private static final String UNTITLED = "未命名文档";

    /**
     * 子块向量仓库注入点保持 {@code @Lazy}（字段注入经按类型回退解析，懒代理生效）：
     * 应用启动不依赖 Milvus 在线，首次使用知识库时才建连/建集合。
     */
    @Lazy
    @Resource
    private RepositoryStorable repository;

    @Resource(name = "parentSplitter")
    private DocumentSplitter parentSplitter;

    @Resource(name = "childSplitter")
    private DocumentSplitter childSplitter;

    @Resource
    private KnowledgeBaseStore knowledgeBaseStore;

    @Resource
    private RagProperties properties;

    @Resource(name = "ragIngestExecutor")
    private ExecutorService ingestExecutor;

    // ------------------------------------------------------------
    // 对外入口

    /**
     * 入库一段原始文本（调试入口：粘贴语义，来源类型 text，文档格式默认 markdown）
     *
     * @param text  文本内容
     * @param title 标题（可选；缺省取文档内一级标题，再兜底"未命名文档"）
     */
    public IngestResult ingestText(String text, String title) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        SourceDocument source = new SourceDocument(null, text,
                resolveTitle(SourceDocument.DOC_TYPE_MARKDOWN, text, title, null),
                null, null, "text", SourceDocument.DOC_TYPE_MARKDOWN);
        return ingestSources(Collections.singletonList(source));
    }

    /**
     * 入库服务器上的单个文件（一个文件 = 一条文档记录，按扩展名确定文档格式）
     *
     * @param path 文件绝对路径
     */
    public IngestResult ingestFile(String path) throws IOException {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        return ingestSources(Collections.singletonList(buildFileSource(file)));
    }

    /**
     * 批量入库一个目录下的所有支持文件（每个文件一条文档记录）
     *
     * @param path      目录绝对路径
     * @param recursive 是否递归扫描子目录（默认 false，仅扫描当前目录一层）
     */
    public IngestResult ingestDirectory(String path, boolean recursive) throws IOException {
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }

        List<File> files = collectFiles(dir, recursive);
        if (files.isEmpty()) {
            log.warn("No files found under directory: {}", path);
            return new IngestResult(0, 0, 0, 0);
        }

        Collections.sort(files);
        List<SourceDocument> sources = new ArrayList<>(files.size());
        for (File f : files) {
            sources.add(buildFileSource(f));
        }
        return ingestSources(sources);
    }

    /**
     * 入库一个 URL 指向的文本资源（TextLoader 拉取全文，文档格式 text）
     *
     * @param url 资源地址
     */
    public IngestResult ingestUrl(String url) throws IOException {
        List<Document> loaded = new TextLoader(new URL(url)).load();
        StringBuilder raw = new StringBuilder();
        for (Document doc : loaded) {
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(doc.getContent());
        }
        String text = raw.toString();
        if (text.trim().isEmpty()) {
            throw new IllegalArgumentException("URL content is empty: " + url);
        }
        SourceDocument source = new SourceDocument(null, text,
                resolveTitle(SourceDocument.DOC_TYPE_TEXT, text, null, urlLastSegment(url)),
                url, null, "url", SourceDocument.DOC_TYPE_TEXT);
        return ingestSources(Collections.singletonList(source));
    }

    /**
     * 入库单个源文档（知识库管理「上传文件 / 粘贴内容」入口）
     * <p>
     * 与调试入口的区别：返回值携带 documentId（管理页面需要据此展示/预览/重建）。
     * 源文档的 title 应已由调用方按规则解析（{@link #resolveTitle}）。
     */
    public DocumentMutationResult ingestSource(SourceDocument source) throws IOException {
        long start = System.currentTimeMillis();
        source = withResolvedDocumentId(source);
        DocStats stats = ingestOne(source);
        return new DocumentMutationResult(source.getDocumentId(), stats.parentCount,
                stats.childCount, System.currentTimeMillis() - start);
    }

    /**
     * 重建索引：依据 {@code kb_document.content} 重新执行切分流水线
     * （章节切分 → 父块 token 切分 → MySQL 父块行替换 → 子块向量化写入 Milvus）。
     * <p>
     * 文档行本身不重建（title/source/docType/content 不变），仅替换父块行并更新
     * chunk_count；旧 Milvus 子块的按 parent_id 批量删除由调用方
     * （KnowledgeBaseService）在本方法之前完成。失败时尽力标记 FAILED 后原样上抛。
     *
     * @param docRow 既有文档行（须含 content / docType）
     */
    public DocumentMutationResult rebuildIndex(KbDocument docRow) throws IOException {
        long start = System.currentTimeMillis();
        String documentId = docRow.getId();
        SourceDocument source = new SourceDocument(documentId, docRow.getContent(), docRow.getTitle(),
                docRow.getSource(), null, docRow.getContentType(), docRow.getDocType());
        String label = docLabel(source);

        SplitResult split = splitSource(source, documentId);
        try {
            knowledgeBaseStore.replaceChunks(documentId, split.parentRows, split.parentRows.size());
            repository.save(split.children, (batch, total) -> logSaveProgress(label, batch, total));
            knowledgeBaseStore.markIngested(documentId);
        } catch (IOException | RuntimeException e) {
            knowledgeBaseStore.markFailed(documentId);
            throw e;
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] document '{}' reindexed: chunks={} children={} elapsed={}ms",
                label, split.parentRows.size(), split.children.size(), elapsed);
        return new DocumentMutationResult(documentId, split.parentRows.size(), split.children.size(), elapsed);
    }

    // ------------------------------------------------------------

    /**
     * 统一入库：逐源文档执行流水线。
     * <p>
     * 多源文档时按"单线程处理最小维度 = 单个源文档"提交到线程池并行处理
     * （见 {@link #ingestInParallel}）；任一文档失败则取消未开始的任务并抛出其原始异常，
     * 语义与串行一致。单文档直接在调用线程处理，避免无谓的线程切换开销。
     */
    private IngestResult ingestSources(List<SourceDocument> sources) throws IOException {
        long start = System.currentTimeMillis();

        boolean parallel = sources.size() > 1;
        DocStats totals = parallel ? ingestInParallel(sources) : ingestSerial(sources);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] sources={} chunks={} children={} elapsed={}ms parallel={} collection={}",
                sources.size(), totals.parentCount, totals.childCount, elapsed, parallel,
                properties.getRepository().getCollectionName());
        return new IngestResult(sources.size(), totals.parentCount, totals.childCount, elapsed);
    }

    /**
     * 串行入库：逐源文档在调用线程执行流水线（单文档/空列表路径）
     */
    private DocStats ingestSerial(List<SourceDocument> sources) throws IOException {
        int parentCount = 0;
        int childCount = 0;
        for (SourceDocument source : sources) {
            DocStats stats = ingestOne(source);
            parentCount += stats.parentCount;
            childCount += stats.childCount;
        }
        return new DocStats(parentCount, childCount);
    }

    /**
     * 并行入库：每个源文档一个任务（切分 → MySQL 落库 → 批量 embedding → Upsert 写入），
     * 聚合父块/子块数。
     * <p>
     * 任务粒度 = 单个源文档：一个文档的完整入库流程在同一根线程内完成，不跨文档拆分任务。
     * 分块器、MilvusClientV2（gRPC 客户端）、EmbeddingModel（按次调用）、MyBatis Mapper
     * 均无共享可变状态（或线程安全），可安全并发调用；并行主要摊平各文档的 embedding 网络耗时。
     * 保存时带进度回调，各文档按批次打印进度（label 区分文档）。
     * <p>
     * 失败语义：按提交顺序等待各任务，首个失败的文档会取消尚未开始的任务
     * （执行中的不打断，让其自然完成）并原样抛出该任务的异常（IOException /
     * RuntimeException 不被包装），与串行路径的异常语义保持一致。
     */
    private DocStats ingestInParallel(List<SourceDocument> sources) throws IOException {
        List<Future<DocStats>> futures = new ArrayList<>(sources.size());
        for (SourceDocument source : sources) {
            futures.add(ingestExecutor.submit(() -> ingestOne(source)));
        }

        int parentCount = 0;
        int childCount = 0;
        try {
            for (Future<DocStats> future : futures) {
                DocStats stats = future.get();
                parentCount += stats.parentCount;
                childCount += stats.childCount;
            }
            return new DocStats(parentCount, childCount);
        } catch (ExecutionException e) {
            // 某个文档失败：取消未开始的任务，抛出原始异常
            cancelFutures(futures, false);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("rag ingestion failed", cause);
        } catch (InterruptedException e) {
            // 调用线程被中断：取消并中断所有任务后恢复中断标记
            Thread.currentThread().interrupt();
            cancelFutures(futures, true);
            throw new IllegalStateException("rag ingestion interrupted", e);
        }
    }

    /**
     * 线程池任务体：单个源文档的入库流水线
     *
     * @return 该文档的父块数 / 子块数
     */
    private DocStats ingestOne(SourceDocument source) throws IOException {
        long start = System.currentTimeMillis();
        source = withResolvedDocumentId(source);
        String documentId = source.getDocumentId();
        String label = docLabel(source);

        SplitResult split = splitSource(source, documentId);

        try {
            // 1. MySQL：文档行 + 父块行（同一事务，状态 INGESTING）
            knowledgeBaseStore.createDocumentWithChunks(
                    buildDocumentRow(source, documentId, split.parentRows.size()), split.parentRows);

            // 2. Milvus：子块批量向量化 + Upsert（parent_id 独立字段），带批次进度回调
            repository.save(split.children, (batch, total) -> logSaveProgress(label, batch, total));

            // 3. 状态推进：INGESTED
            knowledgeBaseStore.markIngested(documentId);
        } catch (IOException | RuntimeException e) {
            // MySQL↔Milvus 双写失败：尽力标记 FAILED（不阻断原异常上抛）
            knowledgeBaseStore.markFailed(documentId);
            throw e;
        }

        log.info("[RAG Ingest] source '{}' ingested: chunks={} children={} elapsed={}ms",
                label, split.parentRows.size(), split.children.size(), System.currentTimeMillis() - start);
        return new DocStats(split.parentRows.size(), split.children.size());
    }

    /**
     * 章节切分 + 父块/子块两级切分（不含落库与向量写入）
     * <p>
     * 章节切分（{@link #sectionize}）后父块<b>逐章节</b>独立切分（每章节用 parentSplitter
     * 单独切，避免跨章节合并正文）；{@code chunk_index} 在文档内全局连续编号（跨章节不断号）。
     * 父块继承章节 metadata（md 场景含 title/title_path/category/type）并补充来源信息
     * （source/filename）；子块在其上追加 document_id / parent_id / child_index（跨组件契约）。
     */
    private SplitResult splitSource(SourceDocument source, String documentId) throws IOException {
        List<Document> sections = sectionize(source);

        List<KbChunk> parentRows = new ArrayList<>();
        List<Document> children = new ArrayList<>();
        int chunkIndex = 0;

        for (Document section : sections) {
            List<Document> parents = parentSplitter.split(Collections.singletonList(section));
            for (Document parent : parents) {
                String parentId = KnowledgeBaseStore.newId();

                // 父块继承章节 metadata + 来源信息（每次构造新 map，避免共享底层 map）
                Document parentDoc = new Document(parent.getContent(), inheritedMetadata(section, source));
                List<Document> kids = childSplitter.split(Collections.singletonList(parentDoc));
                for (int j = 0; j < kids.size(); j++) {
                    Document child = kids.get(j);
                    child.metadata(META_DOCUMENT_ID, documentId)
                            .metadata(META_PARENT_ID, parentId)
                            .metadata(META_CHILD_INDEX, j);
                    children.add(child);
                }

                KbChunk row = new KbChunk();
                row.setId(parentId);
                row.setDocumentId(documentId);
                row.setChunkIndex(chunkIndex++);
                row.setTitle(sectionTitlePath(section));
                row.setContent(parent.getContent());
                row.setCharCount(parent.getContent() == null ? 0 : parent.getContent().length());
                row.setChildCount(kids.size());
                parentRows.add(row);
            }
        }
        return new SplitResult(parentRows, children);
    }

    /**
     * 章节切分（markdown 专属多章节；text/html 单章节，无子标题路径）：
     * <ul>
     *   <li>markdown：{@code MarkdownLoader} 的上下文继承章节切分——其输出的每个 Document
     *       重定义为"章节"，内容自带 "### 路径\n正文" 前缀，metadata 携带
     *       title / title_path（" / " 连接）/ category；</li>
     *   <li>text：原文单章节；</li>
     *   <li>html：HtmlSimpleLoader 转出纯文本（多片段拼接）单章节。</li>
     * </ul>
     */
    private List<Document> sectionize(SourceDocument source) throws IOException {
        String raw = source.getRawText() == null ? "" : source.getRawText();
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

        if (SourceDocument.DOC_TYPE_HTML.equals(source.getDocType())) {
            List<Document> loaded = new HtmlSimpleLoader(bytes).load();
            StringBuilder text = new StringBuilder();
            for (Document doc : loaded) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(doc.getContent());
            }
            return Collections.singletonList(new Document(text.toString()));
        }
        if (SourceDocument.DOC_TYPE_TEXT.equals(source.getDocType())) {
            return Collections.singletonList(new Document(raw));
        }
        return new MarkdownLoader(bytes).load();
    }

    /**
     * 汇总父块继承的 metadata：章节自身元数据 + 来源信息（source/filename）。
     * 每次构造新 HashMap，避免 MarkdownLoader 章节 Document 间共享底层 map 的污染。
     */
    private Map<String, Object> inheritedMetadata(Document section, SourceDocument source) {
        Map<String, Object> meta = new HashMap<>();
        if (section.getMetadata() != null) {
            meta.putAll(section.getMetadata());
        }
        if (source.getSource() != null) {
            meta.put("source", source.getSource());
        }
        if (source.getFilename() != null) {
            meta.put("filename", source.getFilename());
        }
        return meta;
    }

    /**
     * {@code kb_chunk.title}：所属章节的完整子标题路径（markdown 场景来自
     * MarkdownLoader 的 title_path 元数据，" / " 连接；text/html 场景为 null）
     */
    private String sectionTitlePath(Document section) {
        Object titlePath = section.getMetadata("title_path");
        if (titlePath == null) {
            return null;
        }
        String text = titlePath.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 文件 → SourceDocument（一个文件 = 一个源文档）：
     * 原样读取全文（UTF-8），docType 按后缀映射，title = 文档内一级标题 → 文件名去后缀
     */
    private SourceDocument buildFileSource(File file) throws IOException {
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String docType = docTypeOf(file.getName());
        String title = resolveTitle(docType, raw, null, stripExtension(file.getName()));
        return new SourceDocument(null, raw, title, file.getAbsolutePath(), file.getName(), "file", docType);
    }

    /**
     * 文档标题解析（优先级：用户填写 → 文档内一级标题（按 docType 提取）→ fallback → 未命名文档）
     * <p>
     * text 无标题语法，不参与一级标题提取；markdown 行级扫描 / html h1-title 正则
     * 见 {@link MarkdownTitleExtractor}。
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
        return UNTITLED;
    }

    /**
     * 构建文档行（一个源文档一行）：content = 原始全文（重建索引依据）、
     * docType = 文档格式、content_chars = 原始全文字符数
     */
    private KbDocument buildDocumentRow(SourceDocument source, String documentId, int chunkCount) {
        KbDocument row = new KbDocument();
        row.setId(documentId);
        row.setTitle(hasText(source.getTitle()) ? source.getTitle() : null);
        row.setContentType(source.getContentType());
        row.setDocType(source.getDocType());
        row.setContent(source.getRawText());
        row.setContentChars(source.getRawText() == null ? 0 : source.getRawText().length());
        row.setChunkCount(chunkCount);
        row.setStatus(KnowledgeBaseStore.STATUS_INGESTING);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 文档 id 兜底：未指定时生成 UUID hex（SourceDocument 不可变，返回新对象）
     */
    private SourceDocument withResolvedDocumentId(SourceDocument source) {
        if (source.getDocumentId() != null && !source.getDocumentId().trim().isEmpty()) {
            return source;
        }
        return new SourceDocument(KnowledgeBaseStore.newId(), source.getRawText(), source.getTitle(),
                source.getSource(), source.getFilename(), source.getContentType(), source.getDocType());
    }

    /**
     * 按扩展名映射文档格式（仅 collectFiles 白名单内文件会走到这里）：
     * .md/.markdown → markdown、.txt → text、.html/.htm → html
     */
    private String docTypeOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return SourceDocument.DOC_TYPE_MARKDOWN;
        }
        if (lower.endsWith(".txt")) {
            return SourceDocument.DOC_TYPE_TEXT;
        }
        return SourceDocument.DOC_TYPE_HTML;
    }

    /**
     * 收录目录下所有支持的文件（.md/.markdown/.html/.htm/.txt）
     *
     * @param dir       起始目录
     * @param recursive 是否递归进入子目录
     */
    private List<File> collectFiles(File dir, boolean recursive) {
        List<File> result = new ArrayList<>();
        File[] entries = dir.listFiles();
        if (entries == null) {
            return result;
        }
        for (File entry : entries) {
            if (entry.isFile() && isSupported(entry.getName())) {
                result.add(entry);
            } else if (recursive && entry.isDirectory()) {
                result.addAll(collectFiles(entry, true));
            }
        }
        return result;
    }

    private boolean isSupported(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".txt");
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * URL 标题兜底：取 URL 末段（去尾斜杠、去扩展名）
     */
    private String urlLastSegment(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String segment = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        segment = stripExtension(segment);
        return hasText(segment) ? segment : null;
    }

    /**
     * 入库进度回调（save(List, BiConsumer)）：save 内部按嵌入 batchSize 分批
     * 向量化 + Upsert，每完成一批回调一次。
     * <p>
     * label 用于区分来源：并行入库时多个文档的进度日志交错打印，靠它辨认是哪个文档。
     *
     * @param label 文档标识（title / source，无则 untitled）
     * @param batch 当前批次（1 开始）
     * @param total 总批次数
     */
    private void logSaveProgress(String label, int batch, int total) {
        log.info("[RAG Ingest] '{}' saving progress: batch {}/{}", label, batch, total);
    }

    /**
     * 取消任务；mayInterruptIfRunning=false 时仅取消尚未开始的任务
     */
    private void cancelFutures(List<Future<DocStats>> futures, boolean mayInterruptIfRunning) {
        for (Future<DocStats> future : futures) {
            future.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * 日志用文档标识：优先 title，其次 source
     */
    private String docLabel(SourceDocument source) {
        if (hasText(source.getTitle())) {
            return source.getTitle();
        }
        return hasText(source.getSource()) ? source.getSource() : "untitled";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 单源文档切分结果（父块行 + 子块 Document）
     */
    private static final class SplitResult {
        final List<KbChunk> parentRows;
        final List<Document> children;

        SplitResult(List<KbChunk> parentRows, List<Document> children) {
            this.parentRows = parentRows;
            this.children = children;
        }
    }

    /**
     * 单文档入库统计（父块数 / 子块数）
     */
    private static final class DocStats {
        final int parentCount;
        final int childCount;

        DocStats(int parentCount, int childCount) {
            this.parentCount = parentCount;
            this.childCount = childCount;
        }
    }
}
