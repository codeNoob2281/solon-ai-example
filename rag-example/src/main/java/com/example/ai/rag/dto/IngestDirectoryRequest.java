package com.example.ai.rag.dto;

import lombok.Data;

/**
 * 目录批量入库请求
 * POST /rag/ingest/directory
 */
@Data
public class IngestDirectoryRequest {
    /** 目录绝对路径（递归扫描 .md/.markdown/.html/.htm/.txt 文件） */
    private String path;
}
