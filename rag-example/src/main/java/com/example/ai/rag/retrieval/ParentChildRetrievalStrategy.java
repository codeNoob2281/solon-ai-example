package com.example.ai.rag.retrieval;

import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.service.RagIngestionService;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认检索策略：父子分段 / Small-to-Big（策略标识 {@code parent-child}），
 * 由原 {@code RagSearchService} 内置检索实现抽离而来。
 * <p>
 * 按「相似度阈值 + limit」从 Milvus 检索<b>子块</b>（小粒度、召回准），
 * 再把子块映射回 MySQL 父块行，以<b>父块内容</b>作为进入 LLM 的上下文
 * （大粒度、上下文完整）。
 * <p>
 * 映射规则（{@link #mapToParentHits}）：
 * <ul>
 *   <li>子块按命中顺序以 metadata.parent_id 分组（LinkedHashMap 保序），同一父块的
 *       多个子块取最高相似度作为该父块的得分；</li>
 *   <li>按父块 id 批量查 MySQL（selectBatchIds）取父块内容；父块行缺失时 WARN 跳过不失败；</li>
 *   <li>输出按父块最高子块相似度倒序。</li>
 * </ul>
 * <p>
 * {@code RepositoryStorable} 注入点保持 {@code @Lazy}（与入库服务一致）：
 * 应用启动不依赖 Milvus 在线，首次检索时才建连/建集合。
 */
@Slf4j
@Component
public class ParentChildRetrievalStrategy implements RagRetrievalStrategy {

    private final RepositoryStorable repository;
    private final KnowledgeBaseStore knowledgeBaseStore;

    public ParentChildRetrievalStrategy(@Lazy RepositoryStorable repository,
                                        KnowledgeBaseStore knowledgeBaseStore) {
        this.repository = repository;
        this.knowledgeBaseStore = knowledgeBaseStore;
    }

    @Override
    public String name() {
        return RagRetrievalStrategy.DEFAULT_STRATEGY;
    }

    @Override
    public List<SearchHit> retrieve(String query, int limit, Double similarityThreshold) throws IOException {
        List<Document> childHits = repository.search(buildCondition(query, limit, similarityThreshold));
        List<SearchHit> parentHits = mapToParentHits(childHits);
        log.info("[RAG Retrieval] strategy={} query={} childHits={} parentHits={}",
                name(), query, childHits.size(), parentHits.size());
        return parentHits;
    }

    /**
     * 子块 → 父块映射（父子分段检索的核心步骤）
     * <p>
     * 1. 子块按命中顺序以 metadata.parent_id 分组（同一父块多个子块取最高分）；
     * 2. MySQL 批量取父块行（含内容），行缺失（如人工清理）时 WARN 跳过；
     * 3. 输出按最高子块相似度倒序的 {@link SearchHit}（content = 父块内容）。
     */
    private List<SearchHit> mapToParentHits(List<Document> childHits) {
        if (childHits.isEmpty()) {
            return new ArrayList<>();
        }

        // 按命中顺序分组：parentId → 最高子块得分；并记录每组首个命中子块（用于元数据/来源展示）
        Map<String, Double> parentScores = new LinkedHashMap<>();
        Map<String, Document> firstChildByParent = new LinkedHashMap<>();
        for (Document child : childHits) {
            Object parentId = child.getMetadata(RagIngestionService.META_PARENT_ID);
            if (parentId == null) {
                log.warn("[RAG Retrieval] child without parent_id, skip: childId={}", child.getId());
                continue;
            }
            String pid = parentId.toString();
            Double best = parentScores.get(pid);
            if (best == null) {
                parentScores.put(pid, child.getScore());
                firstChildByParent.put(pid, child);
            } else if (child.getScore() > best) {
                parentScores.put(pid, child.getScore());
            }
        }
        if (parentScores.isEmpty()) {
            return new ArrayList<>();
        }

        // MySQL 批查父块行
        Map<String, KbChunk> parentsById = new HashMap<>();
        for (KbChunk parent : knowledgeBaseStore.findParentsByIds(parentScores.keySet())) {
            parentsById.put(parent.getId(), parent);
        }

        List<SearchHit> result = new ArrayList<>(parentScores.size());
        for (Map.Entry<String, Double> entry : parentScores.entrySet()) {
            KbChunk parent = parentsById.get(entry.getKey());
            if (parent == null) {
                // 子块命中但父块行缺失：跳过不失败，保证问答可用性
                log.warn("[RAG Retrieval] parent row missing in MySQL, skip: parentId={}", entry.getKey());
                continue;
            }
            Document firstChild = firstChildByParent.get(entry.getKey());
            result.add(new SearchHit(parent.getId(), parent.getDocumentId(),
                    entry.getValue(), parent.getContent(), firstChild.getMetadata()));
        }

        // 按最高子块相似度倒序
        result.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        return result;
    }

    /**
     * 构建查询条件
     * <p>
     * limit 默认 QueryCondition.DEFAULT_LIMIT = 4；
     * 相似度阈值默认 QueryCondition.DEFAULT_SIMILARITY_THRESHOLD = 0.4（检索结果按 score >= 阈值二次过滤）
     */
    private QueryCondition buildCondition(String query, int limit, Double similarityThreshold) {
        QueryCondition condition = new QueryCondition(query)
                .limit(limit > 0 ? limit : QueryCondition.DEFAULT_LIMIT);
        if (similarityThreshold != null && similarityThreshold > 0) {
            condition.similarityThreshold(similarityThreshold);
        }
        return condition;
    }
}
