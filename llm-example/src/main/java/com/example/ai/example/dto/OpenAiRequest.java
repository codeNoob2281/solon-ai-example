package com.example.ai.example.dto;

import lombok.Data;

/**
 * AI 对话请求体
 * POST /chat/sendMessage
 */
@Data
public class OpenAiRequest {
    private String message;
}