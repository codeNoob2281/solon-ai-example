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
}