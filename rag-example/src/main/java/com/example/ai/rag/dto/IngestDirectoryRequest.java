package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 目录批量入库请求
 * POST /rag/ingest/directory
 */
@Schema(description = "目录批量入库请求体")
@Data
public class IngestDirectoryRequest {

    /** 目录绝对路径 */
    @Schema(description = "目录绝对路径", example = "D:/docs")
    private String path;

    /** 是否递归扫描子目录（默认 false，仅扫描当前目录一层） */
    @Schema(description = "是否递归扫描子目录（默认 false，仅扫描当前目录一层）", example = "false")
    private boolean recursive;
}
