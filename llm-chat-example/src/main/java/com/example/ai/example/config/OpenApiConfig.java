package com.example.ai.example.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置
 * <p>
 * 引入 springdoc-openapi-ui 后自动生效，无需额外配置：
 * <ul>
 *   <li>Swagger UI：http://localhost:8080/swagger-ui.html</li>
 *   <li>OpenAPI JSON：http://localhost:8080/v3/api-docs</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("llm-chat-example API")
                .description("Solon AI ChatModel 对话示例：非流式/流式（SSE）对话、会话记忆、"
                        + "ChatOptions 参数、预设对话、提示词模板、多模态理解、自定义智能体")
                .version("1.0.0"));
    }
}
