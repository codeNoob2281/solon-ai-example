package com.example.ai.example.service;

import com.example.ai.core.LoggingChatInterceptor;
import com.example.ai.example.dto.ChatOptionsRequest;
import com.example.ai.example.dto.CustomAgentRequest;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
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
     * 流式对话，返回 AI 响应事件流（Reactor Flux）。
     * <p>
     * 4.1 起流式 API 返回 {@code Flux<ChatEvent>}：正文增量按 {@code TEXT_DELTA}
     * 过滤并读取 {@code getText()}，完整终态从 {@code RESPONSE_END.getResponse()} 读取。
     */
    public Flux<ChatEvent> stream(String userMessage) {
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
     * 切换模型
     */
    private ChatModel switchModel(String modelName) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(chatModel.getConfig().getApiUrl());
        config.setApiKey(chatModel.getConfig().getApiKey());
        config.setProvider(chatModel.getConfig().getProvider());
        config.setModel(modelName);
        return ChatModel.of(config)
                .defaultInterceptorAdd(new LoggingChatInterceptor())
                .build();
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
     * 自定义智能体对话（非流式）。
     * <p>
     * 模拟 agent 应用中的"自定义智能体"功能：传入角色 + 系统指令 + 用户消息，
     * 动态构建一个带 role / instruction 的 ChatModel 完成单轮对话。
     * <pre>{@code
     * ChatModel agent = ChatModel.of(apiUrl)
     *         .apiKey(apiKey)
     *         .model(model)
     *         .role(role)
     *         .instruction(instruction)
     *         .build();
     * }</pre>
     * apiUrl / apiKey / model 未传时复用全局默认 ChatModel 的配置。
     *
     * @param request 自定义智能体请求
     * @return AI 响应文本
     */
    public String chatAsCustomAgent(CustomAgentRequest request) {
        try {
            ChatModel agent = buildCustomAgent(request);
            return agent.prompt(request.getMessage()).call().getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 根据请求参数构建自定义智能体 ChatModel。
     * <p>
     * 与 llm-core 自动装配的全局 ChatModel 相互独立：
     * 这里通过 {@link ChatModel#of(String)} 链式构建，role / instruction 会作为模型级
     * 默认 ChatOptions 持久化，每次 prompt 都会自动合并进系统提示词，无需额外传参。
     */
    private ChatModel buildCustomAgent(CustomAgentRequest req) {
        // 未传时复用全局默认 ChatModel 的配置（apiUrl / apiKey / model）
        String apiUrl = hasText(req.getApiUrl()) ? req.getApiUrl() : chatModel.getConfig().getApiUrl();
        String apiKey = hasText(req.getApiKey()) ? req.getApiKey() : chatModel.getConfig().getApiKey();
        String model = hasText(req.getModel()) ? req.getModel() : chatModel.getModel();

        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .role(req.getRole())
                .instruction(req.getInstruction())
                .defaultInterceptorAdd(new LoggingChatInterceptor())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

    /**
     * 音频理解示例：文本 + 音频 URL，识别音频中的动物叫声。
     * <p>
     * 硬编码参数：
     * <pre>{@code
     * ChatMessage.ofUser("请识别这段音频中是什么动物的叫声？",
     *     AudioBlock.ofUrl("https://cdn.pixabay.com/download/audio/2026/01/24/audio_761a22043a.mp3?filename=dragon-studio-cute-cat-meow-472372.mp3"));
     * }</pre>
     * OpenAI 兼容方言会将 AudioBlock 序列化为 {@code audio_url} 类型内容块。
     */
    public String audioUnderstanding(String modelName) {
        try {
            ChatMessage msg = ChatMessage.ofUser("请识别这段音频中是什么动物的叫声？",
                    AudioBlock.ofUrl("https://cdn.pixabay.com/download/audio/2026/01/24/audio_761a22043a.mp3?filename=dragon-studio-cute-cat-meow-472372.mp3"));
            ChatModel chatModel1 = switchModel(modelName);
            return chatModel1.prompt(msg)
                    .options(op -> op.temperature(0.1d))
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 视频理解示例：文本 + 视频 URL，描述视频内容。
     * <p>
     * 硬编码参数：
     * <pre>{@code
     * ChatMessage.ofUser("请描述这段视频的内容？",
     *     VideoBlock.ofUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"));
     * }</pre>
     * OpenAI 兼容方言会将 VideoBlock 序列化为 {@code video_url} 类型内容块。
     */
    public String videoUnderstanding(String modelName) {
        try {
            ChatMessage msg = ChatMessage.ofUser("请描述这段视频的内容？",
                    VideoBlock.ofUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"));
            ChatModel chatModel1 = switchModel(modelName);
            return chatModel1.prompt(msg)
                    .options(op -> op.temperature(0.1d))
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }
}