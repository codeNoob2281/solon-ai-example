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
}
