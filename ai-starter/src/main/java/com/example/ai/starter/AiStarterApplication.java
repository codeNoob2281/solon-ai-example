package com.example.ai.starter;

import com.example.ai.core.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一启动入口：一个应用聚合 llm-chat-example / rag-example / agent-example 三个示例模块
 * <p>
 * 装配方式为 Spring Boot 自动装配（与 llm-core 的 ChatModelAutoConfiguration 同机制）：
 * 三个示例模块各自在 META-INF/spring.factories 注册自动配置类，本入口只声明依赖
 * （classpath 引入即装配），不扫描各模块包路径：
 * <ul>
 *   <li>llm-chat-example → ChatExampleAutoConfiguration（/chat 对话 API）</li>
 *   <li>rag-example → RagExampleAutoConfiguration（/rag 知识库 API，@MapperScan 由此携带）</li>
 *   <li>agent-example → AgentExampleAutoConfiguration（/agent 与 /api/agent Agent API）</li>
 *   <li>llm-core → ChatModelAutoConfiguration（三个模块共用的 ChatModel Bean）</li>
 * </ul>
 * 启动前从项目根目录 .env 文件加载环境变量；全部模块共用同一份配置与端口，
 * MySQL / Milvus / ChatModel 网络依赖仍按各模块既定策略懒初始化。
 */
@SpringBootApplication
public class AiStarterApplication {

    public static void main(String[] args) {
        // 在 Spring Boot 启动前加载 .env 文件到系统属性
        EnvFileLoader.load();
        SpringApplication.run(AiStarterApplication.class, args);
    }
}
