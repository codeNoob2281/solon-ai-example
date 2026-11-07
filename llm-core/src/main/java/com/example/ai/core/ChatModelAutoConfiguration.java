package com.example.ai.core;

import com.example.ai.core.masking.MaskingChatInterceptor;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
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
    public ChatModel chatModel(ChatModelProperties properties,
                               ObjectProvider<MaskingChatInterceptor> maskingInterceptorProvider) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(properties.getApiUrl());
        config.setApiKey(properties.getApiKey());
        config.setProvider(properties.getProvider());
        config.setModel(properties.getModel());
        // 上下文窗口：用量面板的占用率基准（框架默认 0 = 未声明）
        config.setContextLength(properties.getContextLength());
        ChatModel.Builder builder = ChatModel.of(config);
        // 消息脱敏须在日志拦截器之前（拦截链同序位按注册顺序执行，掩码先于日志打印）
        maskingInterceptorProvider.ifAvailable(builder::defaultInterceptorAdd);
        if (properties.isLoggingEnabled()) {
            builder.defaultInterceptorAdd(new LoggingChatInterceptor());
        }
        return builder.build();
    }
}