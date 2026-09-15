package com.example.ai.rag.retrieval;

import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.entity.KbChunkFullTextRow;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索策略（策略标识 {@code mysql-fulltext}）：
 * 直接对 MySQL 父块表 kb_chunk.content 做 FULLTEXT 全文检索（自然语言模式，
 * ngram 中文分词），按相关度倒序返回父块内容，分值在 SQL 内 ROUND 保留 4 位小数。
 * <p>
 * 与默认策略 {@code parent-child} 的差异：
 * <ul>
 *   <li>不依赖 Milvus 与向量模型，纯关键词匹配（无语义泛化，适合精确词命中场景）；</li>
 *   <li>检索粒度直接为父块（不经子块召回映射）；</li>
 *   <li>分值为 MySQL 全文相关度（非 0~1 相似度），similarityThreshold 语义为
 *       「相关度下限」过滤（SQL 端 ROUND 后比较）。</li>
 * </ul>
 * <p>
 * 依赖与约束：
 * <ul>
 *   <li>FULLTEXT 索引 {@code idx_kb_chunk_content_ft(content)}（ngram 解析器）由
 *       {@link KnowledgeBaseStore} 在本策略首次使用时懒建，需 MySQL 5.7.6+；</li>
 *   <li>仅检索已入库（kb_document.status = INGESTED）文档的父块，与向量路径
 *       （Milvus 只含写成功文档）的可见范围对齐。</li>
 * </ul>
 */
@Slf4j
@Component
public class MysqlFullTextRetrievalStrategy implements RagRetrievalStrategy {

    /**
     * 策略标识（供 {@code ai.rag.search.strategy} / 环境变量 {@code RAG_SEARCH_STRATEGY} 配置）
     */
    public static final String NAME = "mysql-fulltext";

    /**
     * 检索条数默认值（对齐 QueryCondition.DEFAULT_LIMIT）
     */
    private static final int DEFAULT_LIMIT = 4;

    @Resource
    private KnowledgeBaseStore knowledgeBaseStore;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SearchHit> retrieve(String query, int limit, Double similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        List<KbChunkFullTextRow> rows =
                knowledgeBaseStore.findChunksByFullText(query.trim(), effectiveLimit, similarityThreshold);

        List<SearchHit> result = new ArrayList<>(rows.size());
        for (KbChunkFullTextRow row : rows) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            putIfText(metadata, "title", row.getTitle());
            putIfText(metadata, "source", row.getSource());
            result.add(new SearchHit(row.getId(), row.getDocumentId(), row.getScore(), row.getContent(), metadata));
        }

        log.info("[RAG Retrieval] strategy={} query={} hits={}", name(), query, result.size());
        return result;
    }

    /**
     * 元数据字段非空才放入（标题/来源供 /rag/search 展示与 /rag/ask 的来源渲染）
     */
    private void putIfText(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            metadata.put(key, value.trim());
        }
    }
}
