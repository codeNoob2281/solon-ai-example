package com.example.ai.rag.rerank;

import com.example.ai.rag.dto.SearchHit;

import java.io.IOException;
import java.util.List;

/**
 * RAG 重排序策略接口：对检索召回结果（topK）做精排，返回最终 topN。
 * <p>
 * 与检索策略解耦：检索负责「召回足够多的候选」（{@code ai.rag.search.top-k}），
 * 重排序负责「精排并裁剪」（{@code ai.rag.search.top-n}）。实现类决定如何精排：
 * <ul>
 *   <li>{@link ModelRerankStrategy}（标识 {@code model}）：调用 Solon AI
 *       {@code RerankingModel} 对 query / 文档对打分后按相关度倒序；</li>
 *   <li>{@link TruncateRerankStrategy}（标识 {@code truncate}）：不精排，直接取前 topN，
 *       也是未启用重排序或重排服务不可用时的兜底实现。</li>
 * </ul>
 * 扩展新策略：实现本接口并注册为 Spring Bean（{@link #name()} 返回唯一标识），
 * 再把 {@code ai.rag.rerank.strategy} 切到该标识即可。
 */
public interface RagRerankStrategy {

    /** 默认重排序策略标识：调用重排序模型精排 */
    String NAME_MODEL = "model";

    /** 截断策略标识（不精排，取前 topN；同时作为兜底实现） */
    String NAME_TRUNCATE = "truncate";

    /**
     * 策略唯一标识（供 {@code ai.rag.rerank.strategy} 配置选择，需在容器内唯一）
     */
    String name();

    /**
     * 对召回结果重排并裁剪
     *
     * @param query 查询文本
     * @param hits  召回结果（已按检索相关度倒序）
     * @param topN  最终保留条数（&lt;=0 时保留全部）
     * @return 重排后的结果（长度不超过 topN）
     */
    List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) throws IOException;
}
