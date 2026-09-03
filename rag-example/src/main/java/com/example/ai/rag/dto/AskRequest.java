package com.example.ai.rag.dto;

import lombok.Data;

/**
 * RAG 问答请求
 * POST /rag/ask
 */
@Data
public class AskRequest {
    /** 用户问题 */
    private String question;
    /** 检索返回的知识条数（默认 4） */
    private Integer limit;
    /** 相似度阈值（0~1，可选；不传时用框架默认 0.4，得分低于阈值的检索结果会被过滤） */
    private Double similarityThreshold;
}
