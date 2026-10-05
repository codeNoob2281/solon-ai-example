package com.example.ai.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Solon AI ChatModel 配置属性
 * 前缀 ai.chat.openai
 */
@Data
@ConfigurationProperties(prefix = "ai.chat.openai")
public class ChatModelProperties {

    /** OpenAI 兼容 API 地址（不带 /v1/chat/completions，会自动拼接） */
    private String apiUrl = "http://localhost:1234/v1";

    /** API 密钥 */
    private String apiKey = "sk-your-api-key";

    /** 模型名称 */
    private String model = "gpt-3.5-turbo";

    /** 提供方标识（OpenAI 协议） */
    private String provider = "openai";

    /**
     * 模型上下文窗口（tokens，读环境变量 AI_CHAT_CONTEXT_LENGTH，默认 128000）。
     * <p>用于用量面板的上下文占用率基准（AgentSession 度量只给 token 明细，不给窗口大小），
     * 同时供框架上下文压缩拦截器（ContextCompressionInterceptor）参考。</p>
     */
    private long contextLength = 128000L;

    /** 是否开启模型请求/响应日志（读环境变量 AI_CHAT_LOGGING_ENABLED，默认关闭） */
    private boolean loggingEnabled = false;
}