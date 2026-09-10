package com.example.ai.rag.service;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.IngestResult;
import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.loader.MarkdownLoader;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentLoader;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.TextLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * RAG 入库流水线（父子分段模式）：
 * 文档加载 → 父块切分（存 MySQL 分块表）→ 子块切分（存 Milvus，独立字段 parent_id 关联父块）
 * <p>
 * 对应参考文档《rag - RAG 相关概念》中的技术概念：
 * <ul>
 *   <li>DocumentLoader：文档加载器（按扩展名选择 Text/Markdown/Html 加载器）</li>
 *   <li>DocumentSplitter：父块分割器（parentSplitter）/ 子块分割器（childSplitter），
 *       均为 TokenSizeTextSplitter（按 token 分块）</li>
 *   <li>RepositoryStorable：子块向量仓库（{@code MilvusChildRepository}，
 *       子块以独立标量字段 parent_id 关联 MySQL 父块行）</li>
 * </ul>
 * <p>
 * 单文档流水线（{@link #ingestSingleDocument}）：
 * <ol>
 *   <li>parentSplitter 切父块（父块继承文档元数据 source/filename/title）；</li>
 *   <li>逐父块 childSplitter 切子块，子块附加 metadata：document_id / parent_id / child_index；</li>
 *   <li>MySQL 落库：文档行（status=INGESTING）+ 父块行（含 child_count），同一事务；</li>
 *   <li>Milvus：子块批量向量化 + Upsert（独立字段 parent_id），带批次进度回调；</li>
 *   <li>成功置 INGESTED；任一步失败尽力置 FAILED 后按原语义抛出。</li>
 * </ol>
 * <p>
 * 并行处理：多文档入库按"单线程处理最小维度 = 单个文档"提交到可配置线程池
 * （ai.rag.ingestion.*），一个文档的完整流水线在同一任务内完成，并行摊平各文档的
 * embedding 网络耗时。已知取舍：MySQL↔Milvus 双写无跨库原子性（示例级可接受）；
 * 同一文档重复入库会以新 id 累积（无按文档删除逻辑）。
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

    private final RepositoryStorable repository;
    private final DocumentSplitter parentSplitter;
    private final DocumentSplitter childSplitter;
    private final KnowledgeBaseStore knowledgeBaseStore;
    private final RagProperties properties;
    private final ExecutorService ingestExecutor;

    public RagIngestionService(@Lazy RepositoryStorable repository,
                               @Qualifier("parentSplitter") DocumentSplitter parentSplitter,
                               @Qualifier("childSplitter") DocumentSplitter childSplitter,
                               KnowledgeBaseStore knowledgeBaseStore,
                               RagProperties properties,
                               @Qualifier("ragIngestExecutor") ExecutorService ingestExecutor) {
        this.repository = repository;
        this.parentSplitter = parentSplitter;
        this.childSplitter = childSplitter;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.properties = properties;
        this.ingestExecutor = ingestExecutor;
    }

    /**
     * 入库一段原始文本
     *
     * @param text  文本内容
     * @param title 标题（可选）
     */
    public IngestResult ingestText(String text, String title) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        Document doc = new Document(text);
        if (hasText(title)) {
            doc.title(title);
        }
        return ingest(Collections.singletonList(doc));
    }

    /**
     * 入库服务器上的单个文件（按扩展名自动选择加载器）
     *
     * @param path 文件绝对路径
     */
    public IngestResult ingestFile(String path) throws IOException {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        return ingest(loadFile(file));
    }

    /**
     * 批量入库一个目录下的所有支持文件
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
        List<Document> all = new ArrayList<>();
        for (File f : files) {
            all.addAll(loadFile(f));
        }
        return ingest(all);
    }

    /**
     * 入库一个 URL 指向的文本资源
     *
     * @param url 资源地址
     */
    public IngestResult ingestUrl(String url) throws IOException {
        DocumentLoader loader = new TextLoader(new java.net.URL(url))
                .additionalMetadata("source", url)
                .additionalMetadata("type", "url");
        return ingest(loader.load());
    }

    // ------------------------------------------------------------

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

    /**
     * 统一入库：逐文档执行父子分段流水线。
     * <p>
     * 多文档时按"单线程处理最小维度 = 单个文档"提交到线程池并行处理
     * （见 {@link #ingestInParallel}）；任一文档失败则取消未开始的任务并抛出其原始异常，
     * 语义与串行一致。单文档直接在调用线程处理，避免无谓的线程切换开销。
     */
    private IngestResult ingest(List<Document> docs) throws IOException {
        long start = System.currentTimeMillis();

        boolean parallel = docs.size() > 1;
        DocStats totals = parallel ? ingestInParallel(docs) : ingestSerial(docs);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] sources={} parents={} children={} elapsed={}ms parallel={} collection={}",
                docs.size(), totals.parentCount, totals.childCount, elapsed, parallel,
                properties.getRepository().getCollectionName());
        return new IngestResult(docs.size(), totals.parentCount, totals.childCount, elapsed);
    }

    /**
     * 串行入库：逐文档在调用线程执行流水线（单文档/空列表路径）
     */
    private DocStats ingestSerial(List<Document> docs) throws IOException {
        int parentCount = 0;
        int childCount = 0;
        for (Document doc : docs) {
            DocStats stats = ingestSingleDocument(doc);
            parentCount += stats.parentCount;
            childCount += stats.childCount;
        }
        return new DocStats(parentCount, childCount);
    }

    /**
     * 并行入库：每个文档一个任务（父子切分 → MySQL 落库 → 批量 embedding → Upsert 写入），
     * 聚合父块/子块数。
     * <p>
     * 任务粒度 = 单个文档：一个文档的完整入库流程在同一根线程内完成，不跨文档拆分任务。
     * 分块器、MilvusClientV2（gRPC 客户端）、EmbeddingModel（按次调用）、MyBatis Mapper
     * 均无共享可变状态（或线程安全），可安全并发调用；并行主要摊平各文档的 embedding 网络耗时。
     * 保存时带进度回调，各文档按批次打印进度（label 区分文档）。
     * <p>
     * 失败语义：按提交顺序等待各任务，首个失败的文档会取消尚未开始的任务
     * （执行中的不打断，让其自然完成）并原样抛出该任务的异常（IOException /
     * RuntimeException 不被包装），与串行路径的异常语义保持一致。
     */
    private DocStats ingestInParallel(List<Document> docs) throws IOException {
        List<Future<DocStats>> futures = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            futures.add(ingestExecutor.submit(() -> ingestSingleDocument(doc)));
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
     * 线程池任务体：单个文档的父子分段入库流水线
     *
     * @return 该文档的父块数 / 子块数
     */
    private DocStats ingestSingleDocument(Document doc) throws IOException {
        long start = System.currentTimeMillis();
        String label = docLabel(doc);

        // 1. 父块切分（父块继承文档元数据）
        List<Document> parents = parentSplitter.split(Collections.singletonList(doc));

        // 2. 逐父块切子块，并附加关联元数据（document_id / parent_id / child_index）
        String documentId = KnowledgeBaseStore.newId();
        List<KbChunk> parentRows = new ArrayList<>(parents.size());
        List<Document> children = new ArrayList<>();

        for (int i = 0; i < parents.size(); i++) {
            Document parent = parents.get(i);
            String parentId = KnowledgeBaseStore.newId();

            Document parentDoc = new Document(parent.getContent(), doc.getMetadata());
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
            row.setChunkIndex(i);
            row.setContent(parent.getContent());
            row.setCharCount(parent.getContent() == null ? 0 : parent.getContent().length());
            row.setChildCount(kids.size());
            parentRows.add(row);
        }

        KbDocument docRow = buildDocumentRow(doc, documentId, parentRows.size());

        try {
            // 3. MySQL：文档行 + 父块行（同一事务，状态 INGESTING）
            knowledgeBaseStore.createDocumentWithChunks(docRow, parentRows);

            // 4. Milvus：子块批量向量化 + Upsert（parent_id 独立字段），带批次进度回调
            repository.save(children, (batch, total) -> logSaveProgress(label, batch, total));

            // 5. 状态推进：INGESTED
            knowledgeBaseStore.markIngested(documentId);
        } catch (IOException | RuntimeException e) {
            // MySQL↔Milvus 双写失败：尽力标记 FAILED（不阻断原异常上抛）
            knowledgeBaseStore.markFailed(documentId);
            throw e;
        }

        log.info("[RAG Ingest] document '{}' ingested: parents={} children={} elapsed={}ms",
                label, parents.size(), children.size(), System.currentTimeMillis() - start);
        return new DocStats(parents.size(), children.size());
    }

    /**
     * 构建文档行（content_type 按 metadata.type / source 推断：url / file / text）
     */
    private KbDocument buildDocumentRow(Document doc, String documentId, int parentCount) {
        KbDocument row = new KbDocument();
        row.setId(documentId);
        row.setTitle(hasText(doc.getTitle()) ? doc.getTitle().trim() : null);
        row.setContentType(resolveContentType(doc));
        row.setSource(asOptText(doc.getMetadata("source")));
        row.setContentChars(doc.getContent() == null ? 0 : doc.getContent().length());
        row.setChunkCount(parentCount);
        row.setStatus(KnowledgeBaseStore.STATUS_INGESTING);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 来源类型推断：metadata.type 优先（url 入库时附加 type=url）；
     * 其次有 source 视为 file，否则为 text
     */
    private String resolveContentType(Document doc) {
        Object type = doc.getMetadata("type");
        if (type != null && !type.toString().trim().isEmpty()) {
            return type.toString().trim();
        }
        return doc.getMetadata("source") != null ? "file" : "text";
    }

    private String asOptText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 入库进度回调（save(List, BiConsumer)）：save 内部按嵌入 batchSize 分批
     * 向量化 + Upsert，每完成一批回调一次。
     * <p>
     * label 用于区分来源：并行入库时多个文档的进度日志交错打印，靠它辨认是哪个文档。
     *
     * @param label 文档标识（title / metadata.source，无则 untitled）
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
     * 日志用文档标识：优先 title，其次 metadata 中的 source
     */
    private String docLabel(Document doc) {
        if (doc.getTitle() != null && !doc.getTitle().trim().isEmpty()) {
            return doc.getTitle();
        }
        Object source = doc.getMetadata() == null ? null : doc.getMetadata().get("source");
        return source != null ? source.toString() : "untitled";
    }

    /**
     * 按扩展名选择文档加载器，并附加 source/filename 元数据
     */
    private List<Document> loadFile(File file) throws IOException {
        String name = file.getName().toLowerCase();
        DocumentLoader loader;
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            loader = new MarkdownLoader(file);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            loader = new HtmlSimpleLoader(file);
        } else {
            loader = new TextLoader(file);
        }

        loader.additionalMetadata("source", file.getAbsolutePath())
                .additionalMetadata("filename", file.getName());
        return loader.load();
    }

    private boolean isSupported(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".txt");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
