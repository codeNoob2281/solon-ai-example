package com.example.ai.agent.dto;

import lombok.Data;

/**
 * 出行规划工作台流式请求体（自定义事件协议，非 AI SDK 协议）
 * <pre>{@code
 * { "id": "前端生成的会话 uuid（多轮澄清续聊）", "message": "我明天下午6点前要从北京到上海" }
 * }</pre>
 */
@Data
public class TravelStreamRequest {

    /**
     * 会话 id（前端生成，后端据此维持多轮 AgentSession）
     */
    private String id;

    /**
     * 用户输入（一句话自然语言需求，或对上一轮澄清追问的补充回答）
     */
    private String message;
}
