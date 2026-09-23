package com.example.ai.rag.controller;

import com.example.ai.rag.dto.DocumentMutationResult;
import com.example.ai.rag.dto.KbChunkListResponse;
import com.example.ai.rag.dto.KbDeleteResult;
import com.example.ai.rag.dto.KbDocumentPage;
import com.example.ai.rag.dto.KbPasteRequest;
import com.example.ai.rag.dto.KbSummary;
import com.example.ai.rag.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（知识库管理页后端，挂 /api 前缀走前端 vite 代理）
 * <pre>
 * GET    /api/rag/kb/summary                 汇总信息（文档数/块数/总字符/向量维度）
 * GET    /api/rag/kb/documents               文档分页列表（标题/来源模糊 + 状态多选）
 * GET    /api/rag/kb/documents/{id}/chunks   文档块列表（预览，按 chunk_index 升序全量）
 * POST   /api/rag/kb/documents/{id}/reindex  重建索引（依据 kb_document.content 重新切分向量化）
 * DELETE /api/rag/kb/documents/{id}          删除文档（Milvus 子块尽力而为，MySQL 事务删除）
 * POST   /api/rag/kb/documents/upload        上传文件新增（multipart，.md/.markdown/.txt/.html/.htm，≤5MB）
 * POST   /api/rag/kb/documents/paste         粘贴内容新增（JSON，format=markdown/text，≤2MB 字符）
 * </pre>
 * <p>
 * 既有调试接口 {@code /rag/*} 保留不删（curl / Swagger 调试用）；错误形态：
 * 业务异常经本控制器局部 {@code @ExceptionHandler} 映射——
 * {@link IllegalArgumentException} → 400、{@link IllegalStateException} → 409，
 * 返回体 {@code {"status":..,"error":..,"message":..}} 供前端按状态码 + message 提示。
 */
@Slf4j
@Tag(name = "RAG 知识库管理", description = "知识库汇总 / 文档分页与预览 / 重建索引 / 删除 / 上传与粘贴新增")
@RestController
@RequestMapping("/api/rag/kb")
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 汇总信息（文档数 / 文档块数 / 文档总字符 / 向量维度）
     * <p>向量维度优先读 Milvus 集合 schema；集合未建时回落嵌入模型（内存缓存）；不可用时为 null
     */
    @Operation(summary = "知识库汇总信息",
            description = "文档数 / 文档块数（kb_chunk 行数）/ 文档总字符（content_chars 之和）/ 向量维度（不可用时为 null）")
    @GetMapping("/summary")
    public KbSummary summary() {
        return knowledgeBaseService.summary();
    }

    /**
     * 文档分页列表（按 updated_at DESC, id DESC 稳定排序）
     *
     * @param pageNum  页码，默认 1
     * @param pageSize 每页条数（白名单 10/20/50/100，非法回落 10）
     * @param title    标题模糊（LIKE %..%，可选）
     * @param source   来源模糊（LIKE %..%，可选）
     * @param status   状态多选（重复参数或逗号分隔均可，如 status=INGESTED,FAILED；空=全部）
     */
    @Operation(summary = "文档分页列表",
            description = "标题/来源模糊过滤 + 状态多选（重复参数或逗号分隔），按更新时间倒序；pageSize 白名单 10/20/50/100")
    @GetMapping("/documents")
    public KbDocumentPage documents(
            @Parameter(description = "页码（1 起）", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数（10/20/50/100）", example = "10")
            @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "标题模糊过滤（可选）")
            @RequestParam(required = false) String title,
            @Parameter(description = "来源模糊过滤（可选）")
            @RequestParam(required = false) String source,
            @Parameter(description = "状态多选（INGESTED/INGESTING/FAILED，可重复或逗号分隔；空=全部）")
            @RequestParam(required = false) List<String> status) {
        return knowledgeBaseService.pageDocuments(pageNum, pageSize, title, source, status);
    }

    /**
     * 文档块列表（预览）：按 chunk_index 升序全量返回，content 为父块全文
     */
    @Operation(summary = "文档块列表（预览）", description = "按 chunk_index 升序全量返回（无懒加载），content 为父块内容全文")
    @GetMapping("/documents/{id}/chunks")
    public KbChunkListResponse chunks(@PathVariable("id") String id) {
        return knowledgeBaseService.listChunks(id);
    }

    /**
     * 重建索引：依据 kb_document.content 重新执行「章节切分 + 父块 token 切分 + 子块向量化」；
     * content 为空的存量旧行返回 400「缺少原始内容，请删除后重新上传」；进行中返回 409
     */
    @Operation(summary = "重建索引",
            description = "旧 Milvus 子块按 parent_id 删除 → MySQL 父块行替换 → 子块重新向量化写入；"
                    + "content 为空返回 400，文档处理中返回 409")
    @PostMapping("/documents/{id}/reindex")
    public DocumentMutationResult reindex(@PathVariable("id") String id) throws IOException {
        return knowledgeBaseService.reindex(id);
    }

    /**
     * 删除文档：MySQL 两表同事务删除；Milvus 子块删除尽力而为（失败不阻断，孤儿子块无召回危害）
     */
    @Operation(summary = "删除文档",
            description = "MySQL kb_chunk + kb_document 同事务删除；Milvus 子块尽力而为删除（失败仅告警不阻断）")
    @DeleteMapping("/documents/{id}")
    public KbDeleteResult delete(@PathVariable("id") String id) {
        return knowledgeBaseService.delete(id);
    }

    /**
     * 上传文件新增（multipart/form-data）
     *
     * @param file  必填，后缀白名单 .md/.markdown/.txt/.html/.htm，≤ 5MB
     * @param title 可选，覆盖标题解析
     */
    @Operation(summary = "上传文件新增",
            description = "后缀白名单 .md/.markdown/.txt/.html/.htm（否则 400），≤ 5MB；"
                    + "title 可选覆盖标题解析；同步入库，返回文档 id 与块数统计")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentMutationResult upload(
            @Parameter(description = "上传文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "标题覆盖（可选）")
            @RequestParam(value = "title", required = false) String title) throws IOException {
        return knowledgeBaseService.upload(file, title);
    }

    /**
     * 粘贴内容新增（application/json）
     * <pre>{@code
     * { "title": "可选标题", "format": "markdown", "content": "# 标题\n正文…" }
     * }</pre>
     * format：markdown（默认）| text；content 必填非空 ≤ 2MB 字符
     */
    @Operation(summary = "粘贴内容新增",
            description = "format 限 markdown/text（默认 markdown）；content 必填非空 ≤ 2MB 字符；"
                    + "source 为空（手动粘贴），返回文档 id 与块数统计")
    @PostMapping("/documents/paste")
    public DocumentMutationResult paste(@RequestBody KbPasteRequest request) {
        return knowledgeBaseService.paste(request);
    }

    // ------------------------------------------------------------
    // 局部异常映射（不引入全局异常处理器）：前端统一按 HTTP 状态码 + message 提示

    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        return errorBody(400, "Bad Request", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Map<String, Object> handleConflict(IllegalStateException e) {
        return errorBody(409, "Conflict", e.getMessage());
    }

    private static Map<String, Object> errorBody(int status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("message", message == null || message.trim().isEmpty() ? "请求处理失败" : message);
        return body;
    }
}
