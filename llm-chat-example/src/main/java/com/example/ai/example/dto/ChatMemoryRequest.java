package com.example.ai.example.dto;

import lombok.Data;

/**
 * 带记忆的对话请求体（基于 FileChatSession）
 * <p>
 * POST /chat/memory/send          非流式
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Data
public class ChatMemoryRequest {

    /**
     * 会话 id：同一 sessionId 共享同一份对话记忆。
     * 对应会话文件 temp/chat-memory/{sessionId}.messages.ndjson
     */
    private String sessionId;

    /** 用户消息 */
    private String message;
}
