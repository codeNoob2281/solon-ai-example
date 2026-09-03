package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单文件入库请求
 * POST /rag/ingest/file
 */
@Schema(description = "单文件入库请求体")
@Data
public class IngestFileRequest {

    /** 服务器本地文件绝对路径（支持 .md/.markdown/.html/.htm/.txt） */
    @Schema(description = "服务器本地文件绝对路径（支持 .md/.markdown/.html/.htm/.txt）",
            example = "D:/docs/sample.md")
    private String path;
}
