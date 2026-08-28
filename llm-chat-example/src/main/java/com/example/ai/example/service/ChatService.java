package com.example.ai.example.service;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import reactor.core.publisher.Flux;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * AI 对话服务：封装 Solon AI ChatModel 调用
 */
@Service
public class ChatService {

    @Resource
    private ChatModel chatModel;

    /**
     * 单轮对话，返回 AI 响应文本
     */
    public String chat(String userMessage) {
        try {
            return chatModel.prompt(userMessage).call().getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 流式对话，返回 AI 响应流（Reactor Flux）
     * 每个 ChatResponse 通过 getContent() 提供增量文本
     */
    public Flux<ChatResponse> stream(String userMessage) {
        return chatModel.prompt(userMessage).stream();
    }
}