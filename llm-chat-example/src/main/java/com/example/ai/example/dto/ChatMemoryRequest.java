package com.example.ai.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 带记忆的对话请求体（基于 FileChatSession）
 * <p>
 * POST /chat/memory/send          非流式
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Schema(description = "带记忆的对话请求体（基于 FileChatSession）")
@Data
public class ChatMemoryRequest {

    /**
     * 会话 id：同一 sessionId 共享同一份对话记忆。
     * 对应会话文件 temp/chat-memory/{sessionId}.messages.ndjson
     */
    @Schema(description = "会话 id：同一 sessionId 共享同一份对话记忆，"
            + "对应会话文件 temp/chat-memory/{sessionId}.messages.ndjson", example = "session-1")
    private String sessionId;

    /** 用户消息 */
    @Schema(description = "用户消息", example = "我叫小明，请记住我的名字")
    private String message;
}
