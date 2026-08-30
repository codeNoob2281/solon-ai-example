package com.example.ai.core;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Solon AI ChatModel 自动装配
 * - 基于 ai.chat.openai.* 配置构建 ChatModel Bean
 * - OpenAI 方言按类名自动注册（无需 Solon 容器）
 * - apiUrl 不带 /v1/chat/completions 会自动拼接
 */
@Configuration
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "ai.chat.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChatModelProperties.class)
public class ChatModelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatModel chatModel(ChatModelProperties properties) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(properties.getApiUrl());
        config.setApiKey(properties.getApiKey());
        config.setProvider(properties.getProvider());
        config.setModel(properties.getModel());
        ChatModel.Builder builder = ChatModel.of(config);
        if (properties.isLoggingEnabled()) {
            builder.defaultInterceptorAdd(new LoggingChatInterceptor());
        }
        return builder.build();
    }
}