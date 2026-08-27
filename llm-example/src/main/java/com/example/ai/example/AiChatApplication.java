package com.example.ai.example;

import com.example.ai.core.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7.18 + Solon AI 4.0.6 对话示例入口
 * 启动前从项目根目录 .env 文件加载环境变量
 */
@SpringBootApplication
public class AiChatApplication {

    public static void main(String[] args) {
        // 在 Spring Boot 启动前加载 .env 文件到系统属性
        EnvFileLoader.load();
        SpringApplication.run(AiChatApplication.class, args);
    }
}