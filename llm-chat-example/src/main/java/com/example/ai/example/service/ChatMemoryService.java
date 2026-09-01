package com.example.ai.example.service;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.FileChatSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link FileChatSession} 的对话记忆服务。
 *
 * <p>大语言模型接口本身是无状态的，要实现"有记忆"的对话，需要把历史消息一起作为
 * 提示语输入。FileChatSession 会把会话消息持久化到本地磁盘（NDJSON 文件），
 * 程序重启后记忆依然有效，适合本地客户端 / CLI 智能体 / 单机工具等场景。</p>
 *
 * <p>存储策略：</p>
 * <ul>
 *   <li><b>持久化文件保留完整消息历史</b>——每次交互只追加（append）用户消息与 AI 回复，
 *       落盘文件始终是全部历史、不做裁剪，重启后完整可查；</li>
 *   <li><b>模型交互只取最近 n 条</b>——构造请求时通过
 *       {@link ChatSession#getLatestMessages(int)} 取最近窗口（默认 20）条历史作为上下文，
 *       避免上下文随对话无限增长，窗口外的旧消息不发给模型。</li>
 * </ul>
 *
 * <ul>
 *   <li>存储目录：{@code temp/chat-memory}（可配置 {@code ai.chat.session.memory-dir}）</li>
 *   <li>会话文件：{@code temp/chat-memory/{sessionId}.messages.ndjson}</li>
 *   <li>交互窗口：20（可配置 {@code ai.chat.session.window-size}）</li>
 * </ul>
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Service
public class ChatMemoryService {

    @Resource
    private ChatModel chatModel;

    /** 会话记忆存储目录（相对路径，基于应用运行目录），可被 application.yml / 环境变量覆盖 */
    @Value("${ai.chat.session.memory-dir:temp/chat-memory}")
    private String memoryDir;

    /** 交互窗口大小：模型交互时取最近的消息条数（默认 20） */
    @Value("${ai.chat.session.window-size:20}")
    private int windowSize;

    /**
     * 带记忆的对话（非流式）。
     * <p>
     * 流程：
     * <ol>
     *   <li>根据 sessionId 加载（或创建）文件会话，磁盘上的完整历史作为记忆源；</li>
     *   <li>取最近 {@code windowSize} 条历史作为上下文，拼上本轮用户消息；</li>
     *   <li>调用模型（不使用 {@code .session()}，避免把全部历史发给模型），
     *       模型只会看到窗口内的消息；</li>
     *   <li>把本轮用户消息与 AI 回复追加写入会话文件，完整历史得以保留。</li>
     * </ol>
     *
     * @param sessionId   会话 id（同一 sessionId 共享同一份记忆）
     * @param userMessage 用户消息
     * @return AI 响应文本
     */
    public String chatWithMemory(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        try {
            // 1. 加载或创建文件会话（首次访问自动创建 temp/chat-memory/{sessionId}.messages.ndjson），
            //    文件里保留全部历史，这里只是把历史读入内存缓存
            ChatSession session = new FileChatSession(sessionId.trim(), memoryDir);

            // 2. 取最近 windowSize 条历史作为上下文（getLatestMessages 会做智能窗口切分，
            //    保证不截断到半句 user/assistant 对话）
            List<ChatMessage> window = session.getLatestMessages(windowSize);

            // 3. 组装请求消息 = 最近 windowSize 条历史 + 本轮用户消息
            ChatMessage userChatMessage = ChatMessage.ofUser(userMessage);
            List<ChatMessage> messages = new ArrayList<>(window);
            messages.add(userChatMessage);

            // 4. 调用模型：这里不用 .session(session)（它会发送会话里的全部历史），
            //    直接传窗口消息，确保模型只看到最近 n 条
            ChatResponse response = chatModel.prompt(messages).call();

            // 5. 保存原始的用户消息和助手消息（复用对象，不重新 new），
            //    追加进会话文件（append），完整历史落盘
            session.addMessage(userChatMessage);
            if (response.getMessage() != null) {
                session.addMessage(response.getMessage());
            }

            return response.getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }
}
