package com.example.ai.rag.dto;

import lombok.Data;

/**
 * 文本入库请求
 * POST /rag/ingest/text
 */
@Data
public class IngestTextRequest {
    /** 文本内容 */
    private String text;
    /** 标题（可选，写入 metadata.title） */
    private String title;
}
