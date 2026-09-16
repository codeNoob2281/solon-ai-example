package com.example.ai.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * AI SDK 流式对话请求体（@ai-sdk/react DefaultChatTransport 的 POST payload）
 * <p>
 * AI SDK useChat 每次发送的消息体形如：
 * <pre>{@code
 * {
 *   "id": "chatId（useChat 的会话 id）",
 *   "messages": [
 *     { "id": "msg-1", "role": "user",
 *       "parts": [ { "type": "text", "text": "你好" } ] }
 *   ],
 *   "trigger": "submit-message",
 *   "messageId": "..."
 * }
 * }</pre>
 * 反序列化对未知字段保持宽容（多出的 trigger/messageId 等字段不报错）。
 */
@Data
public class ChatStreamRequest {

    /**
     * 会话 id（前端 useChat 的 id，用于后端维持多轮 AgentSession）
     */
    private String id;

    /**
     * 消息列表（完整对话历史，后端只取最后一条用户消息作为本轮输入）
     */
    private List<UIMessage> messages;

    /**
     * AI SDK UIMessage：role + parts 结构
     */
    @Data
    public static class UIMessage {
        private String id;
        private String role;
        private List<UIPart> parts;
    }

    /**
     * AI SDK UIMessage 的内容分片（本页只产出 text 分片）
     */
    @Data
    public static class UIPart {
        private String type;
        private String text;
    }

    /**
     * 提取最后一条用户消息的纯文本（多个 text 分片按顺序拼接）
     */
    public String lastUserText() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            UIMessage message = messages.get(i);
            if (message == null || !"user".equals(message.getRole())) {
                continue;
            }
            StringBuilder buf = new StringBuilder();
            if (message.getParts() != null) {
                for (UIPart part : message.getParts()) {
                    if (part != null && "text".equals(part.getType())
                            && part.getText() != null && !part.getText().isEmpty()) {
                        if (buf.length() > 0) {
                            buf.append('\n');
                        }
                        buf.append(part.getText());
                    }
                }
            }
            String text = buf.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }
}
