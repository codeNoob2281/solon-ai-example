package com.example.ai.rag.service;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.IngestResult;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentLoader;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.loader.HtmlSimpleLoader;
import org.noear.solon.ai.rag.loader.MarkdownLoader;
import org.noear.solon.ai.rag.loader.TextLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * RAG 入库流水线：文档加载 → 分块 → 向量化 → 写入 Milvus
 * <p>
 * 对应参考文档《rag - RAG 相关概念》中的三个技术概念：
 * <ul>
 *   <li>DocumentLoader：文档加载器（按扩展名选择 Text/Markdown/Html 加载器）</li>
 *   <li>DocumentSplitter：文档分割器（TokenSizeTextSplitter 按 token 分块）</li>
 *   <li>RepositoryStorable：可存储知识库（MilvusRepository，embedding 后写入 Collection）</li>
 * </ul>
 * <p>
 * 入库流程（MilvusRepository.save 内部）：按 batchSize 分批 → EmbeddingModel.embed 批量向量化
 * → Upsert 写入 Milvus，每完成一批通过 progressCallback 回调打印进度日志。文档无 id 时自动生成 UUID。
 * <p>
 * 并行处理：多文档入库按"单线程处理最小维度 = 单个文档"提交到可配置线程池
 * （ai.rag.ingestion.*，见 {@link RagProperties.Ingestion}），一个文档的
 * 分块 → 向量化 → 写入 在同一任务内完成，并行摊平各文档的 embedding 网络耗时。
 */
@Slf4j
@Service
public class RagIngestionService {

    private final RepositoryStorable repository;
    private final DocumentSplitter splitter;
    private final RagProperties properties;
    private final ExecutorService ingestExecutor;

    public RagIngestionService(@Lazy RepositoryStorable repository,
                               DocumentSplitter splitter,
                               RagProperties properties,
                               @Qualifier("ragIngestExecutor") ExecutorService ingestExecutor) {
        this.repository = repository;
        this.splitter = splitter;
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
            return new IngestResult(0, 0, 0);
        }

        Collections.sort(files);
        List<Document> all = new ArrayList<>();
        for (File f : files) {
            all.addAll(loadFile(f));
        }
        return ingest(all);
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
     * 统一入库：分块 → 保存到知识库（内部自动向量化并写入 Milvus）
     * <p>
     * 多文档时按"单线程处理最小维度 = 单个文档"提交到线程池并行处理
     * （见 {@link #ingestInParallel}）；任一文档失败则取消未开始的任务并抛出其原始异常，
     * 语义与串行一致。单文档直接在调用线程处理，避免无谓的线程切换开销。
     */
    private IngestResult ingest(List<Document> docs) throws IOException {
        long start = System.currentTimeMillis();

        boolean parallel = docs.size() > 1;
        int chunkCount = parallel ? ingestInParallel(docs) : ingestSerial(docs);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] source={} chunks={} elapsed={}ms parallel={} collection={}",
                docs.size(), chunkCount, elapsed, parallel,
                properties.getRepository().getCollectionName());
        return new IngestResult(docs.size(), chunkCount, elapsed);
    }

    /**
     * 串行入库：一次分块 + 一次批量保存（单文档/空列表路径），按嵌入批次打印进度
     */
    private int ingestSerial(List<Document> docs) throws IOException {
        List<Document> chunks = splitter.split(docs);
        String label = docs.size() == 1 ? docLabel(docs.get(0)) : "batch";
        repository.save(chunks, (batch, total) -> logSaveProgress(label, batch, total));
        return chunks.size();
    }

    /**
     * 并行入库：每个文档一个任务（分块 → 批量 embedding → Upsert 写入），聚合分块数。
     * <p>
     * 任务粒度 = 单个文档：一个文档的完整入库流程在同一根线程内完成，不跨文档拆分任务。
     * 分块器、MilvusRepository（gRPC 客户端）、EmbeddingModel（按次调用）均无共享可变状态，
     * 可安全并发调用；并行主要摊平各文档的 embedding 网络耗时。保存时带进度回调，
     * 各文档按批次打印进度（label 区分文档）。
     * <p>
     * 失败语义：按提交顺序等待各任务，首个失败的文档会取消尚未开始的任务
     * （执行中的不打断，让其自然完成）并原样抛出该任务的异常（IOException /
     * RuntimeException 不被包装），与串行路径的异常语义保持一致。
     */
    private int ingestInParallel(List<Document> docs) throws IOException {
        List<Future<Integer>> futures = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            futures.add(ingestExecutor.submit(() -> ingestSingleDocument(doc)));
        }

        int chunkCount = 0;
        try {
            for (Future<Integer> future : futures) {
                chunkCount += future.get();
            }
            return chunkCount;
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
     * 线程池任务体：处理单个文档（分块 → 写入知识库），返回该文档的分块数
     */
    private int ingestSingleDocument(Document doc) throws IOException {
        long start = System.currentTimeMillis();
        List<Document> chunks = splitter.split(Collections.singletonList(doc));
        String label = docLabel(doc);
        // 带进度回调的保存重载：每完成一个嵌入批次（embed + Upsert）打印一次进度
        repository.save(chunks, (batch, total) -> logSaveProgress(label, batch, total));
        log.info("[RAG Ingest] document '{}' ingested: chunks={} elapsed={}ms",
                label, chunks.size(), System.currentTimeMillis() - start);
        return chunks.size();
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
    private void cancelFutures(List<Future<Integer>> futures, boolean mayInterruptIfRunning) {
        for (Future<Integer> future : futures) {
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
}
