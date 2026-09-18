package com.example.ai.starter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置（合并版）
 * <p>
 * 三个示例模块各自的 OpenApiConfig 均定义了同名 {@code @Bean openAPI()}，合并启动时
 * bean 名冲突，故在各模块自动装配中被排除，由本类统一提供一份描述：
 * <ul>
 *   <li>Swagger UI：http://localhost:{SERVER_PORT}/swagger-ui.html</li>
 *   <li>OpenAPI JSON：http://localhost:{SERVER_PORT}/v3/api-docs</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("solon-ai-example API (unified)")
                .description("ai-starter 统一入口：llm-chat-example（/chat 对话）+ rag-example（/rag 知识库）"
                        + "+ agent-example（/agent、/api/agent 智能体）")
                .version("1.0.0"));
    }
}
