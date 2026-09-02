package com.example.ai.rag;

import com.example.ai.core.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7.18 + Solon AI 4.0.6 传统 RAG 示例入口
 * <p>
 * 先实现：文档加载（DocumentLoader）→ 分块（DocumentSplitter）→ 入库（MilvusRepository 写入 Milvus）
 * 启动前从项目根目录 .env 文件加载环境变量。
 */
@SpringBootApplication
public class RagExampleApplication {

    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(RagExampleApplication.class, args);
    }
}
