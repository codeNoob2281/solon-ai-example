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
import org.springframework.util.StringUtils;

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
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel(RagProperties props) {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setApiUrl(props.getEmbedding().getApiUrl());
        config.setApiKey(props.getEmbedding().getApiKey());
        config.setProvider(props.getEmbedding().getProvider());
        config.setStandard(props.getEmbedding().getStandard());
        config.setModel(props.getEmbedding().getModel());
        config.setBatchSize(props.getEmbedding().getBatchSize());
        return EmbeddingModel.of(config).build();
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
}
