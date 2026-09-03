package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * RAG 问答请求
 * POST /rag/ask
 */
@Schema(description = "RAG 问答请求体")
@Data
public class AskRequest {

    /** 用户问题 */
    @Schema(description = "用户问题", example = "什么是 RAG？")
    private String question;

    /** 检索返回的知识条数（默认 4） */
    @Schema(description = "检索返回的知识条数（默认 4）", example = "4")
    private Integer limit;

    /** 相似度阈值（0~1，可选；不传时用框架默认 0.4，得分低于阈值的检索结果会被过滤） */
    @Schema(description = "相似度阈值（0~1，可选；不传时用框架默认 0.4，得分低于阈值的检索结果会被过滤）",
            example = "0.5")
    private Double similarityThreshold;
}
