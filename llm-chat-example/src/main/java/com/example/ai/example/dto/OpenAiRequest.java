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

    @Schema(description = "思考模式开关（流式接口生效）：true=开启思考，false=关闭思考，"
            + "不传=请求不携带该参数（模型默认行为）；需模型支持思考",
            example = "true")
    private Boolean thinking;
}
