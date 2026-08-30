package com.example.ai.example.service;

import com.example.ai.example.dto.ChatOptionsRequest;
import org.noear.solon.ai.chat.ChatConfig;
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

    /**
     * 带 ChatOptions 的单轮对话。
     * <p>
     * 演示如何在每次请求中动态设置模型参数：
     * <ul>
     *   <li>contextLength → 构建 ChatConfig（影响上下文压缩窗口）</li>
     *   <li>maxTokens / temperature / reasoningEffort / thinking → ChatOptions（每次请求动态生效）</li>
     * </ul>
     *
     * @param request 对话请求（含消息 + 可选参数）
     * @return AI 响应文本
     */
    public String chatWithOptions(ChatOptionsRequest request) {
        try {
            ChatModel model = buildModel(request.getContextLength());

            return model.prompt(request.getMessage())
                    .options(opts -> applyOptions(opts, request))
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 根据上下文窗口大小构建 ChatModel。
     * contextLength 为 null 或 0 时复用默认 chatModel（避免不必要的对象创建）。
     */
    private ChatModel buildModel(Long contextLength) {
        if (contextLength == null || contextLength <= 0) {
            return chatModel;
        }

        // 从默认 chatModel 的配置复制基础参数，仅覆盖 contextLength
        ChatConfig config = new ChatConfig();
        config.setApiUrl(chatModel.getConfig().getApiUrl());
        config.setApiKey(chatModel.getConfig().getApiKey());
        config.setProvider(chatModel.getConfig().getProvider());
        config.setModel(chatModel.getModel());
        config.setContextLength(contextLength);

        return ChatModel.of(config).build();
    }

    /**
     * 将请求参数应用到 ChatOptions。
     * <p>
     * 注意：reasoning_effort 与 thinking 的优先级关系由 Solon AI 内部处理：
     * thinking(false) 关闭优先于 reasoning_effort；仅设 reasoning_effort 时
     * 多数方言会隐式开启 thinking。
     */
    private void applyOptions(org.noear.solon.ai.chat.ChatOptions opts, ChatOptionsRequest req) {
        if (req.getMaxTokens() != null && req.getMaxTokens() > 0) {
            opts.max_tokens(req.getMaxTokens());
        }
        if (req.getTemperature() != null) {
            opts.temperature(req.getTemperature());
        }
        if (req.getTopP() != null) {
            opts.top_p(req.getTopP());
        }
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            opts.reasoning_effort(req.getReasoningEffort());
        }
        if (req.getThinking() != null) {
            opts.thinking(req.getThinking());
        }
    }
}