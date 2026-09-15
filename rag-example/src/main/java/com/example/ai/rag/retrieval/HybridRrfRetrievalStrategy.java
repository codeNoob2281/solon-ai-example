package com.example.ai.rag.retrieval;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索策略（策略标识 {@code hybrid-rrf}）：对向量检索（{@code parent-child}）
 * 与关键词检索（{@code mysql-fulltext}）的结果做 RRF（Reciprocal Rank Fusion）融合排序。
 * <p>
 * 融合公式（每个命中按其在来源列表中的名次贡献分值）：
 * {@code finalScore = vectorWeight * Σ 1/(RRF_K + rank) + keywordWeight * Σ 1/(RRF_K + rank)}
 * <ul>
 *   <li>RRF_K 固定为 60（标准取值，避免单一来源头名过度主导）；权重取自
 *       {@code ai.rag.search.vector-weight / keyword-weight}（默认 0.6 / 0.4，原始权重不归一化）；</li>
 *   <li>两来源各自检索 {@code limit × candidate-multiplier}（默认 3 倍）条候选，
 *       融合排序后取相关度前 limit；融合分数统一 ROUND 保留 4 位小数；</li>
 *   <li>两来源均返回父块级命中（父块 id 对齐），融合时优先保留向量侧命中对象
 *       （含子块级元数据，来源展示更完整）；</li>
 *   <li>{@code similarityThreshold} 原样透传给两个子策略
 *       （向量侧为相似度阈值，关键词侧为相关度下限）；</li>
 *   <li>任一来源检索失败（如 Milvus/MySQL 暂不可用）时降级：WARN 后仅用另一来源结果，
 *       保证混合检索在单端故障时仍可用。</li>
 * </ul>
 */
@Slf4j
@Component
public class HybridRrfRetrievalStrategy implements RagRetrievalStrategy {

    /**
     * 策略标识（供 {@code ai.rag.search.strategy} / 环境变量 {@code RAG_SEARCH_STRATEGY} 配置）
     */
    public static final String NAME = "hybrid-rrf";

    /**
     * RRF 常量 k：避免单一来源头名过度主导，标准取 60
     */
    private static final double RRF_K = 60.0;

    /**
     * 检索条数默认值（limit <= 0 时使用，对齐 QueryCondition.DEFAULT_LIMIT）
     */
    private static final int DEFAULT_LIMIT = 4;

    @Resource
    private ParentChildRetrievalStrategy vectorStrategy;

    @Resource
    private MysqlFullTextRetrievalStrategy keywordStrategy;

    @Resource
    private RagProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SearchHit> retrieve(String query, int limit, Double similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }

        RagProperties.Search cfg = properties.getSearch();
        double vectorWeight = cfg.getVectorWeight();
        double keywordWeight = cfg.getKeywordWeight();
        int multiplier = cfg.getCandidateMultiplier() > 0 ? cfg.getCandidateMultiplier() : 3;
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        int candidateLimit = effectiveLimit * multiplier;

        List<SearchHit> vectorHits = safeRetrieve(vectorStrategy, "vector", query, candidateLimit, similarityThreshold);
        List<SearchHit> keywordHits = safeRetrieve(keywordStrategy, "keyword", query, candidateLimit, similarityThreshold);

        List<SearchHit> fused = fuse(vectorHits, vectorWeight, keywordHits, keywordWeight);
        List<SearchHit> result = fused.size() > effectiveLimit ? fused.subList(0, effectiveLimit) : fused;

        log.info("[RAG Retrieval] strategy={} query={} vectorHits={} keywordHits={} fusedHits={}",
                name(), query, vectorHits.size(), keywordHits.size(), result.size());
        return result;
    }

    /**
     * 单来源检索（带降级）：失败时 WARN 并返回空列表，让另一来源单独支撑融合
     */
    private List<SearchHit> safeRetrieve(RagRetrievalStrategy strategy, String label,
                                         String query, int limit, Double similarityThreshold) {
        try {
            return strategy.retrieve(query, limit, similarityThreshold);
        } catch (IOException | RuntimeException e) {
            log.warn("[RAG Retrieval] {} retrieval failed, degrade to the other source: {}",
                    label, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * RRF 融合：两来源按名次贡献加权得分，按父块 id 合并，输出按融合分倒序
     */
    private List<SearchHit> fuse(List<SearchHit> vectorHits, double vectorWeight,
                                 List<SearchHit> keywordHits, double keywordWeight) {
        Map<String, FusedHit> byId = new LinkedHashMap<>();
        addRanked(byId, vectorHits, vectorWeight);
        addRanked(byId, keywordHits, keywordWeight);

        List<SearchHit> result = new ArrayList<>(byId.size());
        for (FusedHit fused : byId.values()) {
            double score = Math.round(fused.score * 10000.0) / 10000.0;
            result.add(new SearchHit(fused.hit.getId(), fused.hit.getDocumentId(),
                    score, fused.hit.getContent(), fused.hit.getMetadata()));
        }
        result.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        return result;
    }

    /**
     * 累加单个来源的名次贡献：weight * 1/(RRF_K + rank)。
     * 同一父块 id 已存在时仅累加得分，命中对象保留首次来源（向量侧，含子块级元数据）。
     */
    private void addRanked(Map<String, FusedHit> byId, List<SearchHit> hits, double weight) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            double contribution = weight / (RRF_K + (i + 1));
            FusedHit fused = byId.get(hit.getId());
            if (fused == null) {
                byId.put(hit.getId(), new FusedHit(hit, contribution));
            } else {
                fused.score += contribution;
            }
        }
    }

    /** 融合过程持有的命中对象：原始 SearchHit + 累计 RRF 得分 */
    private static final class FusedHit {
        final SearchHit hit;
        double score;

        FusedHit(SearchHit hit, double score) {
            this.hit = hit;
            this.score = score;
        }
    }
}
