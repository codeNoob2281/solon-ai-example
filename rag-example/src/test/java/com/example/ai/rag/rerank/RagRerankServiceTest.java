package com.example.ai.rag.rerank;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.SearchHit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagRerankService} 编排单测：策略成功时按策略结果返回；策略失败时兜底前 topN 截断。
 */
class RagRerankServiceTest {

    private static RagProperties propertiesWithTopN(int topN) {
        RagProperties props = new RagProperties();
        props.getSearch().setTopN(topN);
        // 打开重排并填写 api-url/model，使 resolver 选中 model 策略（测试用 Fake 实现）
        props.getRerank().setEnabled(true);
        props.getRerank().setStrategy(RagRerankStrategy.NAME_MODEL);
        props.getRerank().setApiUrl("http://localhost/rerank");
        props.getRerank().setModel("fake-reranker");
        return props;
    }

    private static SearchHit hit(String id) {
        return new SearchHit(id, "doc-" + id, 1.0, "content-" + id, null);
    }

    private static RagRerankService serviceWith(RagRerankStrategy modelStrategy, RagProperties props) {
        RagRerankStrategyResolver resolver = new RagRerankStrategyResolver(props, Arrays.asList(
                modelStrategy, new TruncateRerankStrategy()));
        return new RagRerankService(resolver, props);
    }

    @Test
    void shouldReturnStrategyResultAndPassTopN() {
        RagProperties props = propertiesWithTopN(2);
        // 假重排：反向输出，可区分于截断结果；同时记录收到的 topN
        int[] receivedTopN = {-1};
        RagRerankStrategy model = new RagRerankStrategy() {
            @Override
            public String name() {
                return NAME_MODEL;
            }

            @Override
            public List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) {
                receivedTopN[0] = topN;
                List<SearchHit> copy = new ArrayList<>(hits);
                Collections.reverse(copy);
                return copy.subList(0, Math.min(topN, copy.size()));
            }
        };

        List<SearchHit> result = serviceWith(model, props)
                .rerank("q", Arrays.asList(hit("a"), hit("b"), hit("c")));

        assertEquals(2, receivedTopN[0]);
        assertEquals(Arrays.asList("c", "b"),
                Arrays.asList(result.get(0).getId(), result.get(1).getId()));
    }

    @Test
    void shouldFallbackToPrefixWhenStrategyFails() {
        RagProperties props = propertiesWithTopN(2);
        RagRerankStrategy failing = new RagRerankStrategy() {
            @Override
            public String name() {
                return NAME_MODEL;
            }

            @Override
            public List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) throws IOException {
                throw new IOException("rerank service unavailable");
            }
        };

        List<SearchHit> result = serviceWith(failing, props)
                .rerank("q", Arrays.asList(hit("a"), hit("b"), hit("c")));

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getId());
        assertEquals("b", result.get(1).getId());
    }

    @Test
    void emptyHitsShouldReturnEmptyWithoutCallingStrategy() {
        RagProperties props = propertiesWithTopN(2);
        RagRerankStrategy model = new RagRerankStrategy() {
            @Override
            public String name() {
                return NAME_MODEL;
            }

            @Override
            public List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) {
                throw new AssertionError("should not be called for empty hits");
            }
        };

        assertTrue(serviceWith(model, props).rerank("q", Collections.emptyList()).isEmpty());
    }
}
