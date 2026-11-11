package com.example.ai.rag.rerank;

import com.example.ai.rag.config.RagProperties;
import com.example.ai.rag.dto.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.reranking.RerankingModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型重排序策略（标识 {@code model}）：调用 Solon AI {@link RerankingModel}
 * 对 query 与召回文档逐一打分，按相关度倒序后做「自适应裁剪」：
 * <ul>
 *   <li>相关性阈值（{@code ai.rag.rerank.score-threshold}，默认 0.4）：低于阈值的结果丢弃；</li>
 *   <li>相邻落差（{@code ai.rag.rerank.score-gap}，默认 0.2）：相邻两条分差大于该值时截断后续，
 *       用于处理相关度断层（如 0.9 之后直接掉到 0.5）；</li>
 *   <li>条数上限 topN（{@code ai.rag.search.top-n}）；</li>
 *   <li>兜底：有重排结果时至少返回 1 条（即使全部低于阈值）。</li>
 * </ul>
 * <p>
 * 实现要点：
 * <ul>
 *   <li>把 {@link SearchHit} 的父块内容包装为 {@link Document}，并在 metadata 写入
 *       原始下标（{@value #META_HIT_INDEX}），重排后据此回填原始命中对象
 *       （id/documentId/metadata 不丢失），仅用重排分数覆盖 score；</li>
 *   <li>{@code RerankingModel} 注入点保持 {@code @Lazy}：应用启动不依赖重排服务在线，
 *       仅在启用并首次调用时创建（构造只选方言，不发起网络请求）；</li>
 *   <li>模型调用异常 / 返回结构异常时抛 {@link IOException}，由
 *       {@code RagRerankService} 统一兜底为前 topN 截断。</li>
 * </ul>
 */
@Slf4j
@Component
public class ModelRerankStrategy implements RagRerankStrategy {

    /** 文档 metadata 中保存原始命中下标的键（重排后回填用） */
    private static final String META_HIT_INDEX = "__rag_hit_index";

    /** 分数比较容差：避免浮点误差把「恰好等于阈值/落差」误判为越界 */
    private static final double SCORE_EPSILON = 1e-9;

    /**
     * {@code RerankingModel} 注入点保持 {@code @Lazy}（字段注入懒代理）：
     * 应用启动不依赖重排服务配置/在线，仅启用并首次重排时初始化。
     */
    @Lazy
    @Resource
    private RerankingModel rerankingModel;

    @Resource
    private RagProperties properties;

    @Override
    public String name() {
        return NAME_MODEL;
    }

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits, int topN) throws IOException {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            documents.add(new Document(hit.getContent()).metadata(META_HIT_INDEX, i));
        }

        List<Document> ranked = rerankingModel.rerank(query, documents);
        if (ranked == null || ranked.isEmpty()) {
            throw new IOException("rerank returned empty result");
        }

        RagProperties.Rerank cfg = properties.getRerank();
        List<Document> selected = selectByThresholdAndGap(ranked, topN,
                cfg.getScoreThreshold(), cfg.getScoreGap());

        List<SearchHit> result = new ArrayList<>(selected.size());
        for (Document doc : selected) {
            result.add(toSearchHit(doc, hits));
        }

        log.info("[RAG Rerank] strategy={} candidates={} kept={} scoreThreshold={} scoreGap={}",
                name(), hits.size(), result.size(), cfg.getScoreThreshold(), cfg.getScoreGap());
        return result;
    }

    /**
     * 按「相关性阈值 + 相邻分数落差 + topN」裁剪已按分数倒序的重排结果（包级可见，便于单测）。
     * <p>
     * 规则：逐条向下；相邻分差大于 {@code scoreGap} 或分数低于 {@code scoreThreshold} 时截断后续；
     * 最多保留 {@code topN} 条（&lt;=0 时不限制）；若全部被裁剪但有结果，至少保留第一条。
     */
    static List<Document> selectByThresholdAndGap(List<Document> ranked, int topN,
                                                  double scoreThreshold, double scoreGap) {
        if (ranked == null || ranked.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = topN > 0 ? topN : ranked.size();
        List<Document> selected = new ArrayList<>(Math.min(limit, ranked.size()));
        Double previousScore = null;
        for (Document doc : ranked) {
            double score = doc.getScore();
            // 相邻落差过大：后续更不相关，直接截断
            if (previousScore != null && previousScore - score > scoreGap + SCORE_EPSILON) {
                break;
            }
            // 低于相关性阈值：后续分数更低，直接截断
            if (score < scoreThreshold - SCORE_EPSILON) {
                break;
            }
            selected.add(doc);
            if (selected.size() >= limit) {
                break;
            }
            previousScore = score;
        }
        // 兜底：有结果时至少返回 1 条
        if (selected.isEmpty()) {
            selected.add(ranked.get(0));
        }
        return selected;
    }

    private SearchHit toSearchHit(Document doc, List<SearchHit> hits) throws IOException {
        int index = toIndex(doc.getMetadata(META_HIT_INDEX));
        if (index < 0 || index >= hits.size()) {
            throw new IOException("rerank result missing hit index, idx=" + index);
        }
        SearchHit source = hits.get(index);
        return new SearchHit(source.getId(), source.getDocumentId(),
                doc.getScore(), source.getContent(), source.getMetadata());
    }

    private int toIndex(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // 落到下方 -1，由调用方抛 IOException 触发兜底
            }
        }
        return -1;
    }
}
