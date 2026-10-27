package com.example.ai.model.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置（独立启动时生效；合并启动由 ai-starter 统一提供）
 * <ul>
 *   <li>Swagger UI：http://localhost:8083/swagger-ui.html</li>
 *   <li>OpenAPI JSON：http://localhost:8083/v3/api-docs</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("model-example API")
                .description("模型配置示例：供应商维护 / 模型维护 / 连通性测试 / 远程模型获取 / 聊天页模型选项")
                .version("1.0.0"));
    }
}
