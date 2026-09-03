package com.example.ai.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 带 ChatOptions 的对话请求体
 * 演示 Solon AI ChatOptions 常用参数的使用方式。
 * <p>
 * POST /chat/sendMessageWithOptions
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Schema(description = "带 ChatOptions 的对话请求体（演示 Solon AI ChatOptions 常用参数）")
@Data
public class ChatOptionsRequest {

    /** 用户消息 */
    @Schema(description = "用户消息", example = "用一句话介绍你自己")
    private String message;

    // ==================== ChatConfig 级别参数（需构建 ChatModel） ====================

    /**
     * 上下文窗口大小（token 数）
     * 影响 Solon AI 的上下文压缩策略：超出该窗口的历史消息会被截断。
     * 对应 ChatConfig#setContextLength(long)
     * 默认 0（不限制）。
     */
    @Schema(description = "上下文窗口大小（token 数），超出该窗口的历史消息会被截断，"
            + "对应 ChatConfig#setContextLength(long)，默认 0（不限制）", example = "4096")
    private Long contextLength;

    // ==================== ChatOptions 级别参数（每次请求动态设置） ====================

    /**
     * 最大输出 token 数
     * 对应 ChatOptions#max_tokens(long)（OpenAI 兼容协议）或
     * ChatOptions#max_completion_tokens(long)（新版协议）。
     * 默认不限制。
     */
    @Schema(description = "最大输出 token 数，对应 ChatOptions#max_tokens(long)，默认不限制", example = "1024")
    private Long maxTokens;

    /**
     * 温度（控制输出随机性）
     * 范围 0.0 ~ 2.0，越高越有创意，越低越确定。
     * 对应 ChatOptions#temperature(double)
     */
    @Schema(description = "温度（0.0~2.0），越高越有创意、越低越确定，对应 ChatOptions#temperature(double)",
            example = "0.7")
    private Double temperature;

    /**
     * top_p 采样（核采样，从累计概率达 p 的最小词集中选择）
     * 范围 0.0 ~ 1.0，与 temperature 通常二选一使用。
     * 对应 ChatOptions#top_p(double)
     */
    @Schema(description = "top_p 核采样（0.0~1.0），与 temperature 通常二选一使用，对应 ChatOptions#top_p(double)",
            example = "0.9")
    private Double topP;

    /**
     * 思考强度
     * 取值：low / medium / high / max
     * 对应 ChatOptions#reasoning_effort(String)
     * 不同方言会映射到供应商字段（如 DeepSeek → reasoning_effort=high，
     * Qwen → thinking.type=enabled + enable_thinking）。
     * 传 null 或空串表示不设置。
     */
    @Schema(description = "思考强度，对应 ChatOptions#reasoning_effort(String)，"
            + "不同方言映射为供应商字段（DeepSeek → reasoning_effort，Qwen → thinking）；null 或空串表示不设置",
            example = "high", allowableValues = {"low", "medium", "high", "max"})
    private String reasoningEffort;

    /**
     * 是否开启思考模式（与 reasoningEffort 配合使用）
     * true 开启，false 关闭，null 不设置。
     * 对应 ChatOptions#thinking(Boolean)
     */
    @Schema(description = "是否开启思考模式（与 reasoningEffort 配合使用），"
            + "对应 ChatOptions#thinking(Boolean)；null 不设置", example = "true")
    private Boolean thinking;
}
