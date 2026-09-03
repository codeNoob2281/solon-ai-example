package com.example.ai.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 对话请求体
 * POST /chat/sendMessage
 */
@Schema(description = "AI 对话请求体")
@Data
public class OpenAiRequest {

    @Schema(description = "用户消息", example = "你好，请介绍一下你自己")
    private String message;
}
