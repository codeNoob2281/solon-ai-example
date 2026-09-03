package com.example.ai.rag.dto;

import lombok.Data;

/**
 * 目录批量入库请求
 * POST /rag/ingest/directory
 */
@Data
public class IngestDirectoryRequest {
    /** 目录绝对路径 */
    private String path;

    /** 是否递归扫描子目录（默认 false，仅扫描当前目录一层） */
    private boolean recursive;
}
