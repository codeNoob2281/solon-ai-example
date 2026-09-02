package com.example.ai.rag.dto;

import lombok.Data;

/**
 * 单文件入库请求
 * POST /rag/ingest/file
 */
@Data
public class IngestFileRequest {
    /** 服务器本地文件绝对路径（支持 .md/.markdown/.html/.htm/.txt） */
    private String path;
}
