package com.example.ai.rag.retrieval;

import com.example.ai.rag.dto.SearchHit;

import java.io.IOException;
import java.util.List;

/**
 * RAG 检索策略接口：从知识库中检索与查询文本相关的内容片段
 * <p>
 * 将「检索如何实现」从 {@code RagSearchService} 中抽离：服务层只负责编排
 * （策略检索 → 提示词组装 → LLM 生成），具体检索方式由实现类决定。
 * <p>
 * 策略可配置：通过 {@code ai.rag.search.strategy}（环境变量 {@code RAG_SEARCH_STRATEGY}）
 * 指定实现类的 {@link #name()} 标识，由 {@link RagRetrievalStrategyResolver} 在启动时
 * 完成选择；未配置时使用默认策略 {@link #DEFAULT_STRATEGY}（当前已实现的父子分段检索）。
 * <p>
 * 扩展新策略：实现本接口并注册为 Spring Bean（{@link #name()} 返回唯一标识），
 * 再把配置切到该标识即可，无需改动服务层。
 */
public interface RagRetrievalStrategy {

    /**
     * 默认检索策略标识：父子分段检索（子块向量检索 + 父块映射，
     * 见 {@link ParentChildRetrievalStrategy}）
     */
    String DEFAULT_STRATEGY = "parent-child";

    /**
     * 策略唯一标识（供 {@code ai.rag.search.strategy} 配置选择，需在容器内唯一）
     */
    String name();

    /**
     * 执行检索
     *
     * @param query               查询文本
     * @param limit               检索条数上限（&lt;=0 时由实现取默认值）
     * @param similarityThreshold 相似度阈值（可选；null 或非正数时由实现取默认值）
     * @return 按相关度倒序排列的命中结果
     */
    List<SearchHit> retrieve(String query, int limit, Double similarityThreshold) throws IOException;
}
