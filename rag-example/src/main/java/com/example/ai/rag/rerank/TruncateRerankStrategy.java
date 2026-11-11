package com.example.ai.rag.rerank;

import com.example.ai.rag.dto.SearchHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 截断重排序策略（标识 {@code truncate}）：不调用重排序模型，直接保留前 topN 条。
 * <p>
 * 既是可显式选择的策略（{@code ai.rag.rerank.strategy=truncate}），也是重排序未启用、
 * 未配置或服务不可用时的兜底实现——保证问答链路始终可用。
 */
@Component
public class TruncateRerankStrategy implements RagRerankStrategy {

    @Override
    public String name() {
        return NAME_TRUNCATE;
    }

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) {
        return truncate(hits, topN);
    }

    /**
     * 取前 topN 条（topN &lt;= 0 时保留全部）；返回新列表，不修改入参。
     */
    public static List<SearchHit> truncate(List<SearchHit> hits, int topN) {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = topN > 0 ? Math.min(topN, hits.size()) : hits.size();
        return new ArrayList<>(hits.subList(0, limit));
    }
}
