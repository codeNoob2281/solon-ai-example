package com.example.ai.rag.rerank;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ModelRerankStrategy#selectByThresholdAndGap} 自适应裁剪单测：
 * 相关性阈值、相邻落差截断、topN 上限、至少保留 1 条的兜底。
 */
class ModelRerankStrategySelectTest {

    private static List<Document> docs(double... scores) {
        List<Document> list = new ArrayList<>();
        for (double score : scores) {
            list.add(new Document("content").score(score));
        }
        return list;
    }

    @Test
    void emptyOrNullShouldReturnEmpty() {
        assertTrue(ModelRerankStrategy.selectByThresholdAndGap(null, 4, 0.4, 0.2).isEmpty());
        assertTrue(ModelRerankStrategy.selectByThresholdAndGap(
                Collections.emptyList(), 4, 0.4, 0.2).isEmpty());
    }

    @Test
    void gapGreaterThanThresholdShouldCutFollowing() {
        // 0.9 与 0.5 分差 0.4 > 0.2 → 只保留 0.9
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.5, 0.45), 4, 0.4, 0.2);

        assertEquals(1, selected.size());
        assertEquals(0.9, selected.get(0).getScore(), 1e-9);
    }

    @Test
    void gapEqualToThresholdShouldNotCut() {
        // 0.9 与 0.7 分差恰好 0.2，不大于阈值 → 两条都保留
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.7), 4, 0.1, 0.2);

        assertEquals(2, selected.size());
    }

    @Test
    void scoreBelowThresholdShouldCutFollowing() {
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.85, 0.3, 0.29), 4, 0.4, 1.0);

        assertEquals(2, selected.size());
    }

    @Test
    void topNShouldCapResult() {
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.8, 0.7), 2, 0.1, 1.0);

        assertEquals(2, selected.size());
    }

    @Test
    void nonPositiveTopNShouldNotCap() {
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.8, 0.7), 0, 0.1, 1.0);

        assertEquals(3, selected.size());
    }

    @Test
    void shouldKeepAtLeastOneWhenAllBelowThreshold() {
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.3, 0.2), 4, 0.4, 0.2);

        assertEquals(1, selected.size());
        assertEquals(0.3, selected.get(0).getScore(), 1e-9);
    }

    @Test
    void shouldReturnAllWhenNoCut() {
        List<Document> selected = ModelRerankStrategy.selectByThresholdAndGap(
                docs(0.9, 0.85, 0.8), 4, 0.4, 0.2);

        assertEquals(3, selected.size());
    }
}
