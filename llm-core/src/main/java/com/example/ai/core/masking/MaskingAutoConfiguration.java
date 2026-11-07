package com.example.ai.core.masking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;

/**
 * 消息脱敏自动装配
 * <p>
 * 仅在 {@code ai.chat.masking.enabled=true} 时装配脱敏组件；默认关闭，不改变既有行为。
 * 装配出的 {@link MaskingChatInterceptor} 由 {@code ChatModelAutoConfiguration} 注入到默认 ChatModel 的
 * 默认拦截器链中（也可被业务模块注入，如模型配置模块动态构建的 ChatModel）。
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass(ChatInterceptor.class)
@ConditionalOnProperty(prefix = "ai.chat.masking", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MaskingProperties.class)
public class MaskingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MaskMapStore maskMapStore() {
        return new MaskMapStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecretMasker secretMasker(MaskingProperties properties) {
        return new SecretMasker(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MaskingChatInterceptor maskingChatInterceptor(SecretMasker secretMasker, MaskMapStore maskMapStore) {
        return new MaskingChatInterceptor(secretMasker, maskMapStore);
    }
}
