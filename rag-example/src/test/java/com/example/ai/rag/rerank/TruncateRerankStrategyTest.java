package com.example.ai.rag.rerank;

import com.example.ai.rag.dto.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TruncateRerankStrategy} 截断逻辑单测（边界：null/空、topN 小于/等于/大于/非正）
 */
class TruncateRerankStrategyTest {

    private static List<SearchHit> hits(String... ids) {
        List<SearchHit> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new SearchHit(id, "doc-" + id, 1.0, "content-" + id, null));
        }
        return list;
    }

    @Test
    void nullOrEmptyShouldReturnEmptyList() {
        assertTrue(TruncateRerankStrategy.truncate(null, 4).isEmpty());
        assertTrue(TruncateRerankStrategy.truncate(Collections.emptyList(), 4).isEmpty());
    }

    @Test
    void topNLessThanSizeShouldKeepPrefixOnly() {
        List<SearchHit> result = TruncateRerankStrategy.truncate(hits("a", "b", "c", "d"), 2);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getId());
        assertEquals("b", result.get(1).getId());
    }

    @Test
    void topNLargerThanSizeShouldKeepAll() {
        List<SearchHit> source = hits("a", "b");
        List<SearchHit> result = TruncateRerankStrategy.truncate(source, 10);

        assertEquals(2, result.size());
        assertNotSame(source, result);
    }

    @Test
    void nonPositiveTopNShouldKeepAll() {
        List<SearchHit> result = TruncateRerankStrategy.truncate(hits("a", "b", "c"), 0);

        assertEquals(3, result.size());
    }

    @Test
    void shouldNotMutateInputList() {
        List<SearchHit> source = hits("a", "b", "c");
        TruncateRerankStrategy.truncate(source, 1);

        assertEquals(3, source.size());
    }
}
