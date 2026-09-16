package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 出行规划输入（ReActAgent 出行规划示例）
 * <p>
 * 用户以一句话自然语言提出需求，意图识别与信息澄清全部由 Agent 层完成；
 * 多轮澄清时回传上一次响应中的 {@code sessionId} 续聊。
 * <pre>{@code
 * 第一轮：{ "message": "我明天下午6点前要从北京到上海，帮我规划一下" }
 * 第二轮：{ "message": "从北京出发", "sessionId": "上一次响应返回的会话ID" }
 * }</pre>
 */
@Data
@Schema(description = "出行规划输入（一句话自然语言；多轮澄清时回传 sessionId）")
public class TravelPlanRequest {

    @Schema(description = "用户出行需求（一句话自然语言，可口语化、可信息不全）",
            example = "我明天下午6点前要从北京到上海，帮我规划一下")
    private String message;

    @Schema(description = "会话ID（可选；多轮澄清时回传上一次响应中的 sessionId）")
    private String sessionId;
}
