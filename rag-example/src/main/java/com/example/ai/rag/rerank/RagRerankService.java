package com.example.ai.rag.rerank;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排序编排服务：把「召回 topK → 精排 → 保留 topN」的重排序阶段从问答服务中抽离。
 * <p>
 * 职责：
 * <ul>
 *   <li>启动时经 {@link RagRerankStrategyResolver} 选定策略（未启用/未配置时即截断策略）；</li>
 *   <li>调用策略精排；策略抛异常（重排模型服务不可用、返回异常等）时兜底为
 *       「召回结果前 topN 截断」，保证问答链路不因重排失败而中断。</li>
 * </ul>
 */
@Slf4j
@Service
public class RagRerankService {

    private final RagRerankStrategy strategy;
    private final int topN;

    public RagRerankService(RagRerankStrategyResolver resolver, RagProperties properties) {
        this.strategy = resolver.resolve();
        this.topN = properties.getSearch().getTopN();
        log.info("[RAG Rerank] strategy={} topN={}", strategy.name(), topN);
    }

    /**
     * 对召回结果重排并保留 topN
     *
     * @param query 查询文本
     * @param hits  召回结果（已按检索相关度倒序）
     * @return 重排后的最终结果；hits 为空时返回空列表
     */
    public List<SearchHit> rerank(String query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return strategy.rerank(query, hits, topN);
        } catch (Exception e) {
            log.warn("[RAG Rerank] strategy={} failed, fallback to topN truncation: {}",
                    strategy.name(), e.getMessage());
            return TruncateRerankStrategy.truncate(hits, topN);
        }
    }
}
