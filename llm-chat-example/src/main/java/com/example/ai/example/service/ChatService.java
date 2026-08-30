package com.example.ai.example.service;

import com.example.ai.example.dto.ChatOptionsRequest;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
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
     */
    public Flux<ChatResponse> stream(String userMessage) {
        return chatModel.prompt(userMessage).stream();
    }

    /**
     * 预设对话示例：天气查询场景。
     * <p>
     * 系统提示词（通过 .systemPrompt() 设置）："你是一个天气查询助手"
     * 对话历史：
     * user:      "今天杭州天气怎么样？"
     * assistant: "杭州今天天气多云，气温22-28度，微风。"
     * 最终提问："下午要带伞吗？"
     * <p>
     * AI 基于完整上下文回答最终提问。
     */
    public String presetConversation() {
        try {
            return chatModel.prompt(
                            ChatMessage.ofUser("今天杭州天气怎么样？"),
                            ChatMessage.ofAssistant("杭州今天天气多云，气温22-28度，微风。"),
                            ChatMessage.ofUser("下午要带伞吗？")
                    )
                    .systemPrompt("你是一个天气查询助手")
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 提示词模板示例：讲笑话场景。
     * <p>
     * 使用 Solon AI 的 UserMessageTemplate + SnEL 模板引擎：
     * <pre>{@code
     * ChatMessage.ofUserTmpl("给我讲一个关于#{subject}的笑话")
     *     .paramAdd("subject", subject)
     *     .generate();
     * }</pre>
     * <p>
     * 系统提示词（硬编码）："你喜欢讲笑话，但是大家并不觉得好笑"
     * 用户只需传入主题。
     *
     * @param subject 笑话主题
     * @return AI 响应文本
     */
    public String promptTemplate(String subject) {
        try {
            ChatMessage msg = ChatMessage.ofUserTmpl("给我讲一个关于#{subject}的笑话")
                    .paramAdd("subject", subject)
                    .generate();

            return chatModel.prompt(msg)
                    .systemPrompt("你喜欢讲笑话，但是大家并不觉得好笑")
                    .options(op -> op
                            .temperature(0.9d)
                            .reasoning_effort("max"))
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 带 ChatOptions 的单轮对话。
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
     */
    private ChatModel buildModel(Long contextLength) {
        if (contextLength == null || contextLength <= 0) {
            return chatModel;
        }

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

    /**
     * 图像理解示例：文本 + 图片 URL。
     * <p>
     * 硬编码参数：
     * <pre>{@code
     * ChatMessage.ofUser("这是什么动物？",
     *     ImageBlock.ofUrl("https://img-s.msn.cn/tenant/amp/entityid/AA23nVpu.img"));
     * }</pre>
     */
    public String imageUnderstanding() {
        try {
            ChatMessage msg = ChatMessage.ofUser("这是什么动物？",
                    ImageBlock.ofUrl("https://img-s.msn.cn/tenant/amp/entityid/AA23nVpu.img"));

            return chatModel.prompt(msg).call().getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }
}