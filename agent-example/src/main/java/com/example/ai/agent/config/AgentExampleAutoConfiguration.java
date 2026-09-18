package com.example.ai.agent.config;

import com.example.ai.agent.AgentExampleApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * agent-example 模块自动装配（供 ai-starter 统一启动入口聚合）
 * <p>
 * 经本模块 META-INF/spring.factories 注册为自动配置类（机制同 llm-core 的
 * ChatModelAutoConfiguration）：ai-starter 引入本模块依赖后，无需扫描本模块包路径，
 * 其 @Service / @RestController 等 Bean 即自动进入容器；本模块独立启动时
 * AgentExampleApplication 的包扫描同样会命中本类，Spring 按同类去重，行为不变。
 * <p>
 * 排除项（仅合并启动时相关）：
 * <ul>
 *   <li>{@link AgentExampleApplication}：独立启动入口，合并启动时无需注册；</li>
 *   <li>{@link OpenApiConfig}：其 {@code @Bean openAPI()} 与其他模块同名冲突，
 *       合并启动时由 ai-starter 提供统一的 OpenAPI 描述。</li>
 * </ul>
 */
@Configuration
@ComponentScan(
        basePackages = "com.example.ai.agent",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AgentExampleApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OpenApiConfig.class)
        }
)
public class AgentExampleAutoConfiguration {
}
