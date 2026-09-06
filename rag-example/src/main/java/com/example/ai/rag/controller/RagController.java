package com.example.ai.rag.controller;

import com.example.ai.rag.dto.AskRequest;
import com.example.ai.rag.dto.IngestDirectoryRequest;
import com.example.ai.rag.dto.IngestFileRequest;
import com.example.ai.rag.dto.IngestResult;
import com.example.ai.rag.dto.IngestTextRequest;
import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.service.RagIngestionService;
import com.example.ai.rag.service.RagSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * RAG 示例接口（父子分段模式）
 * <pre>
 * POST /rag/ingest/text        原始文本入库（加载 → 父块切分存 MySQL → 子块切分向量化存 Milvus）
 * POST /rag/ingest/file        服务器单文件入库（支持 md/html/txt）
 * POST /rag/ingest/directory   目录批量入库
 * GET  /rag/search?q=xxx       子块向量检索 → 父块映射（验证入库效果，可选相似度阈值）
 * POST /rag/ask                检索增强问答（RAG，父块内容进 LLM 上下文）
 * </pre>
 * 数据分布：父块存 MySQL（kb_document + kb_chunk），子块存 Milvus（独立字段 parent_id 关联父块）。
 */
@Slf4j
@Tag(name = "RAG 知识库", description = "文档加载 → 父块切分存 MySQL → 子块切分存 Milvus（parent_id 关联）→ 子块检索映射父块 → 检索增强问答")
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
    @Operation(summary = "原始文本入库",
            description = "加载原始文本 → 父块切分存 MySQL → 子块切分向量化存 Milvus（parent_id 关联父块），返回文档数/父块数/子块数/耗时")
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
    @Operation(summary = "服务器单文件入库",
            description = "按扩展名自动选择 Markdown/Html/Text 加载器，支持 .md/.markdown/.html/.htm/.txt")
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
    @Operation(summary = "目录批量入库",
            description = "扫描目录下 .md/.markdown/.html/.htm/.txt 文件批量入库，"
                    + "recursive 控制是否递归子目录（默认 false）")
    @PostMapping("/ingest/directory")
    public IngestResult ingestDirectory(@RequestBody IngestDirectoryRequest request) throws IOException {
        return ingestionService.ingestDirectory(request.getPath(), request.isRecursive());
    }

    /**
     * 子块向量检索 → 父块映射：GET /rag/search?q=问题&limit=4&similarityThreshold=0.5
     * <p>相似度阈值可选（0~1），不传时用框架默认值 0.4；
     * 返回结果为映射后的父块（content = 父块内容，score = 命中子块最高相似度）
     */
    @Operation(summary = "子块向量检索（返回映射后的父块）",
            description = "按相似度阈值 + limit 从 Milvus 检索子块 → 子块映射父块 → 按最高子块相似度倒序返回父块，"
                    + "用于验证入库效果；similarityThreshold 可选（0~1），不传时用框架默认值 0.4")
    @GetMapping("/search")
    public List<SearchHit> search(@Parameter(description = "检索问题", required = true, example = "什么是 RAG？")
                                  @RequestParam String q,
                                  @Parameter(description = "返回条数上限", example = "4")
                                  @RequestParam(defaultValue = "4") int limit,
                                  @Parameter(description = "相似度阈值（0~1，可选，默认 0.4）", example = "0.5")
                                  @RequestParam(required = false) Double similarityThreshold) throws IOException {
        return searchService.search(q, limit, similarityThreshold);
    }

    /**
     * RAG 问答：检索子块（相似度倒序）→ 映射父块（父块内容进 LLM 上下文）
     * → 系统提示词（智能客服）+ 用户提示词模版 → ChatModel 生成
     * <pre>{@code
     * { "question": "问题", "limit": 4, "similarityThreshold": 0.5 }
     * }</pre>
     * <p>similarityThreshold 可选（0~1），不传时用框架默认值 0.4；limit 为子块检索条数
     */
    @Operation(summary = "检索增强问答（RAG）",
            description = "检索子块（相似度阈值 + limit）→ 子块映射父块 → 父块内容进 LLM 上下文"
                    + "→ 系统提示词（智能客服）+ 用户提示词模版 → 生成回答；"
                    + "similarityThreshold 可选（0~1），不传时用框架默认值 0.4")
    @PostMapping("/ask")
    public String ask(@RequestBody AskRequest request) throws IOException {
        log.info("[RAG Ask] 收到请求: question={}, limit={}, similarityThreshold={}",
                request.getQuestion(), request.getLimit(), request.getSimilarityThreshold());
        int limit = request.getLimit() == null ? 0 : request.getLimit();
        return searchService.ask(request.getQuestion(), limit, request.getSimilarityThreshold());
    }
}
