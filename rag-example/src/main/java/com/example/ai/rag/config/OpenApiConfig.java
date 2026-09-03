package com.example.ai.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置
 * <p>
 * 引入 springdoc-openapi-ui 后自动生效，无需额外配置：
 * <ul>
 *   <li>Swagger UI：http://localhost:8081/swagger-ui.html</li>
 *   <li>OpenAPI JSON：http://localhost:8081/v3/api-docs</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("rag-example API")
                .description("传统 RAG 示例：文档加载（Text/Markdown/Html）→ 分块 → Milvus 入库 →"
                        + " 向量检索 → 检索增强问答")
                .version("1.0.0"));
    }
}
