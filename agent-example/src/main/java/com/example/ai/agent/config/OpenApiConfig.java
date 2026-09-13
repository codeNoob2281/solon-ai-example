package com.example.ai.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置
 * <p>
 * 引入 springdoc-openapi-ui 后自动生效，无需额外配置：
 * <ul>
 *   <li>Swagger UI：http://localhost:8082/swagger-ui.html</li>
 *   <li>OpenAPI JSON：http://localhost:8082/v3/api-docs</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("agent-example API")
                .description("Solon AI Agent（SimpleAgent）示例：电商用户评论五维度情感分析 + 工具调用（本地 mock 天气查询）")
                .version("1.0.0"));
    }
}
