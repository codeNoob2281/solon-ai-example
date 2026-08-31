package com.example.ai.example.dto;

import lombok.Data;

/**
 * 自定义智能体对话请求体
 * 模拟 agent 应用中的"自定义智能体"功能：
 * 传入角色 + 系统指令 + 用户消息，可选覆盖 API 地址/密钥/模型。
 * <p>
 * POST /chat/customAgent          非流式
 * POST /chat/customAgentStream    流式（SSE）
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Data
public class CustomAgentRequest {

    /** 用户消息 */
    private String message;

    /** 智能体角色，如：财务数据分析师 */
    private String role;

    /** 系统指令，如：你负责分析订单与退款数据。金额单位均为元。 */
    private String instruction;

    // ==================== 可选：覆盖全局配置（默认取 llm-core 装配的 ChatModel） ====================

    /**
     * OpenAI 兼容 API 地址（可带 /v1/chat/completions，也可只到域名根）
     * 对应 ChatModel.Builder#apiUrl
     */
    private String apiUrl;

    /** API 密钥，对应 ChatModel.Builder#apiKey */
    private String apiKey;

    /** 模型名称，对应 ChatModel.Builder#model */
    private String model;
}
