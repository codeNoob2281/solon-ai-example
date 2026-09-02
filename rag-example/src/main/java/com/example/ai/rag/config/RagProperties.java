package com.example.ai.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 示例配置属性，前缀 ai.rag
 * <p>
 * 覆盖三类配置：
 * <ul>
 *   <li>embedding：嵌入模型（默认 Ollama bge-m3，可切换 OpenAI 兼容接口）</li>
 *   <li>milvus：Milvus 连接（对应 milvus-sdk-java 的 {@code io.milvus.v2.client.ConnectConfig}）</li>
 *   <li>repository：知识库 Collection 名</li>
 *   <li>splitter：文本分块参数（TokenSizeTextSplitter）</li>
 * </ul>
 * application.yml 中的 ${RAG_*:默认值} 占位符优先从 .env（经 EnvFileLoader）或系统环境变量解析。
 */
@Data
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

    private Embedding embedding = new Embedding();
    private Milvus milvus = new Milvus();
    private Repository repository = new Repository();
    private Splitter splitter = new Splitter();

    /**
     * 嵌入模型配置
     */
    @Data
    public static class Embedding {
        /** 嵌入 API 地址。Ollama 用完整地址（如 http://127.0.0.1:11434/api/embed）；OpenAI 兼容用 /v1/embeddings */
        private String apiUrl = "http://127.0.0.1:11434/api/embed";
        /** API 密钥（Ollama 可留空） */
        private String apiKey = "";
        /** 模型名称 */
        private String model = "bge-m3:latest";
        /** 服务提供方（匹配 EmbeddingDialect；openai/ollama/dashscope） */
        private String provider = "ollama";
        /** 接口规范（4.0 起，优先于 provider 参与方言匹配） */
        private String standard = "ollama";
        /** 嵌入批次大小 */
        private int batchSize = 10;
    }

    /**
     * Milvus 连接配置（对应 milvus-sdk-java ConnectConfig 的类结构）
     */
    @Data
    public static class Milvus {
        /** Milvus 地址（本地 Standalone 默认 http://localhost:19530；Zilliz Cloud 用 https://...） */
        private String uri = "http://localhost:19530";
        /** Token 认证（Zilliz Cloud 或开启认证的集群） */
        private String token = "";
        /** 用户名（开启认证时） */
        private String username = "";
        /** 密码（开启认证时） */
        private String password = "";
        /** 数据库名（默认 default） */
        private String dbName = "";
    }

    /**
     * 知识库（Collection）配置
     */
    @Data
    public static class Repository {
        /** Milvus Collection 名（不能用 '-'，Milvus 命名限制） */
        private String collectionName = "solon_rag_demo";
    }

    /**
     * 文本分块参数（TokenSizeTextSplitter）
     */
    @Data
    public static class Splitter {
        /** 每块目标 token 数 */
        private int chunkSize = 500;
        /** 块最小字符数（低于该值不输出） */
        private int minChunkSizeChars = 300;
        /** 最小嵌入字符长度 */
        private int minChunkLengthToEmbed = 5;
        /** 单文档最大分块数 */
        private int maxChunkCount = 1000;
        /** 保留分隔符 */
        private boolean keepSeparator = true;
    }
}
