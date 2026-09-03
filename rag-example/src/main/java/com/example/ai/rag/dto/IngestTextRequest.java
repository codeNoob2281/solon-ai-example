package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文本入库请求
 * POST /rag/ingest/text
 */
@Schema(description = "文本入库请求体")
@Data
public class IngestTextRequest {

    /** 文本内容 */
    @Schema(description = "文本内容", example = "RAG（检索增强生成）是一种结合检索与生成的技术……")
    private String text;

    /** 标题（可选，写入 metadata.title） */
    @Schema(description = "标题（可选，写入 metadata.title）", example = "RAG 概念介绍")
    private String title;
}
