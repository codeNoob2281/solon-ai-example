package com.example.ai.agent;

import com.example.ai.core.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7.18 + Solon AI 4.1.0（solon-ai-agent）示例入口
 * <p>
 * 基于 {@link org.noear.solon.ai.agent.simple.SimpleAgent} 实现电商用户评论的
 * 五维度情感分析（商品属性 / 商品质量 / 外观设计 / 使用体验 / 价格价值）。
 * 启动前从项目根目录 .env 文件加载环境变量（由 llm-core 的 EnvFileLoader 提供）。
 */
@SpringBootApplication
public class AgentExampleApplication {

    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(AgentExampleApplication.class, args);
    }
}
