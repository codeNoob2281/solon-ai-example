package com.example.ai.rag.config;

import com.example.ai.rag.repository.MilvusChildRepository;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.DocumentSplitter;
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
 * RAG 组件装配（父子分段模式）
 * <p>
 * 核心组件：
 * <pre>{@code
 * EmbeddingModel        = RetryableEmbeddingModel（超时/瞬断自动重试）
 * ConnectConfig         = ConnectConfig.builder().uri(...).build()
 * MilvusClientV2        = new MilvusClientV2(connectConfig)
 * MilvusChildRepository = 子块向量仓库（自建集合：parent_id 独立字段）
 * parentSplitter        = 父块分割器（父块存 MySQL 分块表）
 * childSplitter         = 子块分割器（子块存 Milvus）
 * }</pre>
 * <p>
 * 说明：
 * <ul>
 *   <li>{@code MilvusChildRepository} 构造时即调用 initRepository()（不存在则自动建集合，
 *       已存在则校验结构），因此标记为 {@link Lazy}：应用启动不要求 Milvus 在线，
 *       首次使用知识库时才建连/建集合。</li>
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
     * 子块向量仓库（父子分段模式，自建集合结构：parent_id 独立标量字段 + TRIE 标量索引）。
     * <p>
     * 不用 solon 内置 {@code MilvusRepository}（其 Collection 结构固定，无法扩展独立字段），
     * 见 {@link MilvusChildRepository}。首次使用时自动建集合，并校验已存在集合的结构兼容性，
     * 因此标记为 {@link Lazy}：应用启动不要求 Milvus 在线，首次使用知识库时才建连/建集合。
     */
    @Bean
    @Lazy
    public MilvusChildRepository milvusChildRepository(EmbeddingModel ragEmbeddingModel,
                                                       MilvusClientV2 milvusClientV2,
                                                       RagProperties props) {
        return new MilvusChildRepository(ragEmbeddingModel, milvusClientV2,
                props.getRepository().getCollectionName());
    }

    /**
     * 父块分割器：文档 → 父块（整体存 MySQL 分块表 kb_chunk，检索命中后进入 LLM 上下文）
     */
    @Bean
    public DocumentSplitter parentSplitter(RagProperties props) {
        RagProperties.Splitter s = props.getSplitter();
        return new TokenSizeTextSplitter(
                s.getParentChunkSize(),
                s.getParentMinChunkSizeChars(),
                s.getMinChunkLengthToEmbed(),
                s.getMaxChunkCount(),
                s.isKeepSeparator());
    }

    /**
     * 子块分割器：父块 → 子块（向量化后存 Milvus，粒度小、召回准，命中后映射回父块）
     */
    @Bean
    public DocumentSplitter childSplitter(RagProperties props) {
        RagProperties.Splitter s = props.getSplitter();
        return new TokenSizeTextSplitter(
                s.getChildChunkSize(),
                s.getChildMinChunkSizeChars(),
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
     * 说明：分割器、{@code MilvusClientV2}（gRPC 客户端）、{@code MyBatis Mapper}、
     * {@code EmbeddingModel}（按次调用）均无共享可变状态（或线程安全），任务可安全并发执行。
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
