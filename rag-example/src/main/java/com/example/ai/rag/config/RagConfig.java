package com.example.ai.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.repository.MilvusRepository;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RAG 组件装配
 * <p>
 * 对应参考文档《solon-ai-repo-milvus》的构建示例：
 * <pre>{@code
 * EmbeddingModel  = EmbeddingModel.of(config).build()
 * ConnectConfig   = ConnectConfig.builder().uri(...).build()
 * MilvusClientV2  = new MilvusClientV2(connectConfig)
 * MilvusRepository= MilvusRepository.builder(embeddingModel, client).build()
 * }</pre>
 * <p>
 * 说明：
 * <ul>
 *   <li>{@code MilvusRepository} 构造时即调用 initRepository()（不存在则自动建 Collection），
 *       因此标记为 {@link Lazy}：应用启动不要求 Milvus 在线，首次使用知识库时才建连/建集合。</li>
 *   <li>{@code MilvusClientV2} 通过 {@code destroyMethod="close"} 在容器关闭时释放连接。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * 嵌入模型：根据 ai.rag.embedding.* 构建。
     * <p>
     * 方言自动匹配（EmbeddingDialectManager 按类名注册，无需 Solon 容器）：
     * <ul>
     *   <li>provider/standard = ollama → OllamaEmbeddingDialect（apiUrl 填完整 /api/embed 地址）</li>
     *   <li>provider/standard 为空 → 默认 OpenaiEmbeddingDialect（OpenAI 兼容 /v1/embeddings）</li>
     * </ul>
     * <p>
     * 可靠性：应用 {@code timeout-seconds} 请求超时（框架默认 120s，挂死的请求会占住
     * 入库线程池线程），并包装为 {@link RetryableEmbeddingModel}——向量接口超时/瞬断/限流
     * 时按指数退避自动重试（{@code retry-max-attempts} / {@code retry-backoff-millis}），
     * 入库与检索两条路径一并生效。
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel(RagProperties props) {
        RagProperties.Embedding e = props.getEmbedding();

        EmbeddingConfig config = new EmbeddingConfig();
        config.setApiUrl(e.getApiUrl());
        config.setApiKey(e.getApiKey());
        config.setProvider(e.getProvider());
        config.setStandard(e.getStandard());
        config.setModel(e.getModel());
        config.setBatchSize(e.getBatchSize());
        if (e.getTimeoutSeconds() > 0) {
            config.setTimeout(Duration.ofSeconds(e.getTimeoutSeconds()));
        }

        return new RetryableEmbeddingModel(config, e.getRetryMaxAttempts(), e.getRetryBackoffMillis());
    }

    /**
     * Milvus 连接配置（milvus-sdk-java ConnectConfig，Lombok Builder）
     */
    @Bean
    public ConnectConfig milvusConnectConfig(RagProperties props) {
        RagProperties.Milvus m = props.getMilvus();
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder().uri(m.getUri());

        if (StringUtils.hasText(m.getToken())) {
            builder.token(m.getToken());
        }
        if (StringUtils.hasText(m.getUsername())) {
            builder.username(m.getUsername());
            if (StringUtils.hasText(m.getPassword())) {
                builder.password(m.getPassword());
            }
        }
        if (StringUtils.hasText(m.getDbName())) {
            builder.dbName(m.getDbName());
        }
        return builder.build();
    }

    /**
     * Milvus 客户端（容器关闭时自动 close）。
     * <p>
     * 注意：{@code MilvusClientV2} 构造函数会发起阻塞的 Connect() RPC（无 Milvus 时约 10s 后
     * DEADLINE_EXCEEDED），因此同样标记为 {@link Lazy}：仅当首次使用知识库时才建连。
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public MilvusClientV2 milvusClientV2(ConnectConfig connectConfig) {
        return new MilvusClientV2(connectConfig);
    }

    /**
     * 可存储知识库（可检索 + 可存储），注入统一接口 {@code org.noear.solon.ai.rag.RepositoryStorable}。
     * 首次使用时自动建 Collection（字段：id/content/metadata/embedding，COSINE 向量索引）。
     */
    @Bean
    @Lazy
    public MilvusRepository milvusRepository(EmbeddingModel ragEmbeddingModel,
                                             MilvusClientV2 milvusClientV2,
                                             RagProperties props) {
        return MilvusRepository.builder(ragEmbeddingModel, milvusClientV2)
                .collectionName(props.getRepository().getCollectionName())
                .build();
    }

    /**
     * 文档分割器：基于 token 数分块（jtokkit cl100k_base）
     */
    @Bean
    public DocumentSplitter documentSplitter(RagProperties props) {
        RagProperties.Splitter s = props.getSplitter();
        return new TokenSizeTextSplitter(
                s.getChunkSize(),
                s.getMinChunkSizeChars(),
                s.getMinChunkLengthToEmbed(),
                s.getMaxChunkCount(),
                s.isKeepSeparator());
    }

    /**
     * 文档入库线程池：批量入库时按"每文档一个任务"提交到该线程池并行处理，
     * 单线程处理最小维度 = 单个文档（分块 → 向量化 → 写入 Milvus 在同一根线程内完成）。
     * <p>
     * 参数来自 {@code ai.rag.ingestion.*}（{@link RagProperties.Ingestion}）：
     * <ul>
     *   <li>核心线程数建议参考嵌入服务的并发能力（Ollama 单模型默认近串行推理，调大无收益）</li>
     *   <li>{@code queue-capacity=0} 时使用 SynchronousQueue：任务不排队直接交接，超过 max 后由提交线程执行</li>
     *   <li>拒绝策略 CallerRunsPolicy：线程池饱和时由提交线程自己执行任务，天然背压、不丢任务</li>
     *   <li>非法参数（max &lt; core、keep-alive &lt; 0 等）构建时兜底归一化，保证应用可启动</li>
     *   <li>容器关闭时调用 {@code shutdown()}（等待已提交任务执行完，不再接收新任务）</li>
     * </ul>
     * 说明：分块器、{@code MilvusRepository}（gRPC 客户端）、{@code EmbeddingModel}
     * （按次调用）均无共享可变状态，任务可安全并发执行。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragIngestExecutor(RagProperties props) {
        RagProperties.Ingestion p = props.getIngestion();

        int corePoolSize = Math.max(1, p.getCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, p.getMaxPoolSize());
        int keepAliveSeconds = Math.max(0, p.getKeepAliveSeconds());
        String prefix = StringUtils.hasText(p.getThreadNamePrefix())
                ? p.getThreadNamePrefix() : "rag-ingest-";

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                p.getQueueCapacity() > 0
                        ? new LinkedBlockingQueue<Runnable>(p.getQueueCapacity())
                        : new SynchronousQueue<Runnable>(),
                new CustomizableThreadFactory(prefix),
                new ThreadPoolExecutor.CallerRunsPolicy());

        // allowCoreThreadTimeOut 要求 keepAliveTime > 0
        if (p.isAllowCoreThreadTimeout() && keepAliveSeconds > 0) {
            executor.allowCoreThreadTimeOut(true);
        }
        return executor;
    }
}
