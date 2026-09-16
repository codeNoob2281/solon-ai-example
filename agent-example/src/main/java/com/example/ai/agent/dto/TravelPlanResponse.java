package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 出行规划接口响应（ReActAgent 出行规划示例）
 * <p>
 * 该结构同时是 Agent 最终输出的 JSON 契约（不含 sessionId，由服务层回填），两种形态二选一：
 * <ul>
 *   <li>needClarification=true：Agent 完成意图识别后判定要素缺失，clarificationMessage 为追问话术，
 *       用户补话后携带 sessionId 继续多轮澄清；</li>
 *   <li>needClarification=false：规划完成，plan 为结构化建议。</li>
 * </ul>
 */
@Data
@Schema(description = "出行规划响应（needClarification=true 时为 Agent 追问，否则 plan 为规划结果）")
public class TravelPlanResponse {

    @Schema(description = "会话ID（多轮澄清时回传该值继续对话）")
    private String sessionId;

    @Schema(description = "是否需要澄清（true=Agent 判定要素缺失待用户补充）", example = "false")
    private Boolean needClarification;

    @Schema(description = "缺失的要素（needClarification=true 时返回，如 departureCity）")
    private List<String> missingFields;

    @Schema(description = "澄清追问话术（needClarification=true 时返回）")
    private String clarificationMessage;

    @Schema(description = "出行规划结果（needClarification=false 时返回）")
    private TravelPlan plan;
}
