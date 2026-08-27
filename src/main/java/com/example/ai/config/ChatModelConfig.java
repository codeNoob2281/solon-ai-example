package com.example.ai.config;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手工构建 Solon AI ChatModel Bean
 * OpenAI 方言按类名自动注册（无需 Solon 容器）
 * apiUrl 不带 /v1/chat/completions 会自动拼接
 */
@Configuration
public class ChatModelConfig {

    @Value("${ai.chat.openai.api-url}")
    private String apiUrl;

    @Value("${ai.chat.openai.api-key}")
    private String apiKey;

    @Value("${ai.chat.openai.model:gpt-3.5-turbo}")
    private String model;

    @Value("${ai.chat.openai.provider:openai}")
    private String provider;

    @Bean
    public ChatModel chatModel() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setProvider(provider);
        config.setModel(model);
        // ChatModel.of(config) 将 ChatConfig 属性拷贝到 Builder
        return ChatModel.of(config).build();
    }
}