package com.example.ai.rag;

import com.example.ai.core.EnvFileLoader;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7.18 + Solon AI 4.0.6 传统 RAG 示例入口（父子分段模式）
 * <p>
 * 链路：文档加载（DocumentLoader）→ 父块切分（存 MySQL kb_chunk）
 * → 子块切分（存 Milvus，独立字段 parent_id 关联父块）
 * → 检索子块 → 映射父块 → 父块进 LLM 上下文。
 * 启动前从项目根目录 .env 文件加载环境变量；MySQL 表与 Milvus 集合均为首次使用时懒初始化。
 */
@SpringBootApplication
@MapperScan("com.example.ai.rag.mapper")
public class RagExampleApplication {

    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(RagExampleApplication.class, args);
    }
}
