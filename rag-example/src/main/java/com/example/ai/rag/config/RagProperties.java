package com.example.ai.rag.config;

import com.example.ai.rag.retrieval.RagRetrievalStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 示例配置属性，前缀 ai.rag
 * <p>
 * 覆盖六类配置：
 * <ul>
 *   <li>embedding：嵌入模型（默认 Ollama bge-m3，可切换 OpenAI 兼容接口）</li>
 *   <li>milvus：Milvus 连接（对应 milvus-sdk-java 的 {@code io.milvus.v2.client.ConnectConfig}）</li>
 *   <li>repository：知识库 Collection 名（子块向量集合，含 parent_id 独立字段）</li>
 *   <li>splitter：父子分段参数（父块存 MySQL / 子块存 Milvus，均用 TokenSizeTextSplitter）</li>
 *   <li>search：检索策略选择（{@link RagRetrievalStrategy} 实现标识，默认父子分段检索）</li>
 *   <li>ingestion：文档并行入库线程池参数（任务粒度 = 单个文档）</li>
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
    private Search search = new Search();
    private Ingestion ingestion = new Ingestion();

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
        /** 嵌入请求超时秒数（<=0 时用框架默认 120s）；挂死的请求会占住入库线程池线程，建议显式配置 */
        private int timeoutSeconds = 60;
        /** 嵌入失败最大尝试次数（含首次；1 = 不重试）。超时/瞬断/限流（429）等瞬态错误适用 */
        private int retryMaxAttempts = 3;
        /** 首次重试退避毫秒数，之后按 2^n 指数递增 */
        private long retryBackoffMillis = 1000;
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
     * 文本分块参数（父子分段模式：父块存 MySQL 分块表，父块再切子块存 Milvus）
     * <p>
     * 父/子两级均使用 {@code TokenSizeTextSplitter}（jtokkit cl100k_base）：
     * chunkSize 为目标 token 数；minChunkSizeChars 仅控制「句子边界回切」的位置；
     * minChunkLengthToEmbed 以下的内容丢弃。
     */
    @Data
    public static class Splitter {
        /** 父块目标 token 数（父块整体存 MySQL，作为检索命中后进入 LLM 上下文的完整段落） */
        private int parentChunkSize = 1000;
        /** 父块句子边界回切最小字符数（在块内最后一个标点回切时，要求回切点超过该字符数） */
        private int parentMinChunkSizeChars = 600;
        /** 子块目标 token 数（子块用于向量化检索，粒度小、召回准） */
        private int childChunkSize = 200;
        /** 子块句子边界回切最小字符数 */
        private int childMinChunkSizeChars = 50;
        /** 最小嵌入字符长度（低于该值的块不输出） */
        private int minChunkLengthToEmbed = 5;
        /** 单文本最大分块数（父块切分与子块切分各自生效） */
        private int maxChunkCount = 1000;
        /** 保留分隔符 */
        private boolean keepSeparator = true;
    }

    /**
     * 检索配置：检索实现抽离为 {@link RagRetrievalStrategy} 策略接口，
     * 由 {@code RagRetrievalStrategyResolver} 按本段配置从容器内全部策略 Bean 中选择
     */
    @Data
    public static class Search {
        /**
         * 检索策略标识（对应 {@link RagRetrievalStrategy#name()}，需在容器内唯一）；
         * 空/未配置时用默认策略；未知标识启动时快速失败。
         * 默认 parent-child = 父子分段检索（子块向量检索 + 父块映射）
         */
        private String strategy = RagRetrievalStrategy.DEFAULT_STRATEGY;
    }

    /**
     * 文档并行入库线程池参数（单线程处理最小维度 = 单个文档：
     * 一个文档的 分块 → 向量化 → 写入 在同一任务内完成，不跨文档拆分）
     */
    @Data
    public static class Ingestion {
        /** 核心线程数（建议参考嵌入服务的并发能力，Ollama 单模型默认近串行推理，过大无收益） */
        private int corePoolSize = 4;
        /** 最大线程数（小于 core-pool-size 时构建处会兜底归一化） */
        private int maxPoolSize = 8;
        /** 任务队列容量；0 表示用 SynchronousQueue 直接交接（任务不排队，超过 max 由提交线程执行） */
        private int queueCapacity = 256;
        /** 空闲线程存活秒数 */
        private int keepAliveSeconds = 60;
        /** 线程名前缀 */
        private String threadNamePrefix = "rag-ingest-";
        /** 核心线程是否参与空闲回收（需 keep-alive-seconds > 0） */
        private boolean allowCoreThreadTimeout = true;
    }
}
