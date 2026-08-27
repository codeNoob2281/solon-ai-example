package com.example.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7.18 + Solon AI 4.0.6 最小示例入口
 * 使用 OpenAI 协议进行 AI 对话
 */
@SpringBootApplication
public class AiChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }
}