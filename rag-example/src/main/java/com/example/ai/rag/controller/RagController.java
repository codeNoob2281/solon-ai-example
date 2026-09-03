package com.example.ai.rag.controller;

import com.example.ai.rag.dto.AskRequest;
import com.example.ai.rag.dto.IngestDirectoryRequest;
import com.example.ai.rag.dto.IngestFileRequest;
import com.example.ai.rag.dto.IngestResult;
import com.example.ai.rag.dto.IngestTextRequest;
import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.service.RagIngestionService;
import com.example.ai.rag.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;

/**
 * RAG 示例接口
 * <pre>
 * POST /rag/ingest/text        原始文本入库（加载 → 分块 → 写入 Milvus）
 * POST /rag/ingest/file        服务器单文件入库（支持 md/html/txt）
 * POST /rag/ingest/directory   目录批量入库
 * GET  /rag/search?q=xxx       向量检索（验证入库效果，可选相似度阈值）
 * POST /rag/ask                检索增强问答（RAG）
 * </pre>
 * 先实现"文档加载、分块、入库"（ingest 系列），检索/问答用于验证入库结果。
 */
@Slf4j
@RestController
@RequestMapping("/rag")
public class RagController {

    @Resource
    private RagIngestionService ingestionService;

    @Resource
    private RagSearchService searchService;

    /**
     * 原始文本入库
     * <pre>{@code
     * {
     *   "text": "内容...",
     *   "title": "标题（可选）"
     * }
     * }</pre>
     */
    @PostMapping("/ingest/text")
    public IngestResult ingestText(@RequestBody IngestTextRequest request) throws IOException {
        return ingestionService.ingestText(request.getText(), request.getTitle());
    }

    /**
     * 服务器单文件入库（按扩展名自动选择 Markdown/Html/Text 加载器）
     * <pre>{@code
     * { "path": "D:/docs/sample.md" }
     * }</pre>
     */
    @PostMapping("/ingest/file")
    public IngestResult ingestFile(@RequestBody IngestFileRequest request) throws IOException {
        return ingestionService.ingestFile(request.getPath());
    }

    /**
     * 目录批量入库（扫描 .md/.markdown/.html/.htm/.txt）
     * <pre>{@code
     * {
     *   "path": "D:/docs",
     *   "recursive": false   // 是否递归子目录，默认 false
     * }
     * }</pre>
     */
    @PostMapping("/ingest/directory")
    public IngestResult ingestDirectory(@RequestBody IngestDirectoryRequest request) throws IOException {
        return ingestionService.ingestDirectory(request.getPath(), request.isRecursive());
    }

    /**
     * 向量检索：GET /rag/search?q=问题&limit=4&similarityThreshold=0.5
     * <p>相似度阈值可选（0~1），不传时用框架默认值 0.4
     */
    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "4") int limit,
                                  @RequestParam(required = false) Double similarityThreshold) throws IOException {
        return searchService.search(q, limit, similarityThreshold);
    }

    /**
     * RAG 问答：检索（相似度倒序）→ 系统提示词（智能客服）+ 用户提示词模版 → ChatModel 生成
     * <pre>{@code
     * { "question": "问题", "limit": 4, "similarityThreshold": 0.5 }
     * }</pre>
     * <p>similarityThreshold 可选（0~1），不传时用框架默认值 0.4
     */
    @PostMapping("/ask")
    public String ask(@RequestBody AskRequest request) throws IOException {
        log.info("[RAG Ask] 收到请求: question={}, limit={}, similarityThreshold={}",
                request.getQuestion(), request.getLimit(), request.getSimilarityThreshold());
        int limit = request.getLimit() == null ? 0 : request.getLimit();
        return searchService.ask(request.getQuestion(), limit, request.getSimilarityThreshold());
    }
}
