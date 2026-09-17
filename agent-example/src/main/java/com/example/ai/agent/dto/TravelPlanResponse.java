package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 出行规划接口响应（ReActAgent 出行规划示例）
 * <p>
 * content 为 Agent 的自然语言回复，响应格式不做限制（可为 Markdown 格式）：
 * 既可能是要素齐备后的出行规划建议，也可能是要素缺失时的澄清追问——
 * 用户补话后携带 sessionId 继续多轮对话即可。
 */
@Data
@Schema(description = "出行规划响应（content 为 Agent 自然语言回复，可为 Markdown 格式）")
public class TravelPlanResponse {

    @Schema(description = "会话ID（多轮对话时回传该值继续对话）")
    private String sessionId;

    @Schema(description = "Agent 自然语言回复（出行建议或澄清追问，可为 Markdown 格式）")
    private String content;
}
