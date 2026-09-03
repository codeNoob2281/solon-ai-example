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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * → Upsert 写入 Milvus。文档无 id 时自动生成 UUID。
 */
@Slf4j
@Service
public class RagIngestionService {

    private final RepositoryStorable repository;
    private final DocumentSplitter splitter;
    private final RagProperties properties;

    public RagIngestionService(@Lazy RepositoryStorable repository,
                               DocumentSplitter splitter,
                               RagProperties properties) {
        this.repository = repository;
        this.splitter = splitter;
        this.properties = properties;
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
     */
    private IngestResult ingest(List<Document> docs) throws IOException {
        long start = System.currentTimeMillis();

        // 1. 分块
        List<Document> chunks = splitter.split(docs);

        // 2. 写入知识库（批量 embedding + Upsert 到 Milvus）
        repository.save(chunks);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] source={} chunks={} elapsed={}ms collection={}",
                docs.size(), chunks.size(), elapsed, properties.getRepository().getCollectionName());
        return new IngestResult(docs.size(), chunks.size(), elapsed);
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
