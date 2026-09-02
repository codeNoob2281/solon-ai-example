package com.example.ai.rag.service;

import com.example.ai.rag.dto.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索与问答服务
 * <p>
 * 对应参考文档《rag - RAG 相关概念》中的应用示例：
 * <pre>{@code
 * // 知识库检索
 * List<Document> context = repository.search(message);
 * // 大模型交互（提示语增强：检索结果格式化为上下文）
 * ChatResponse resp = chatModel.prompt(ChatMessage.ofUserAugment(message, context)).call();
 * }</pre>
 * <p>
 * 检索由 MilvusRepository 完成（query 向量化 → Milvus 向量相似度检索 → 重过滤）；
 * 问答使用 llm-core 自动装配的全局 ChatModel。
 */
@Slf4j
@Service
public class RagSearchService {

    private final RepositoryStorable repository;
    private final ChatModel chatModel;

    public RagSearchService(@Lazy RepositoryStorable repository, ChatModel chatModel) {
        this.repository = repository;
        this.chatModel = chatModel;
    }

    /**
     * 向量检索：返回与 query 最相似的知识块
     *
     * @param query 查询文本
     * @param limit 返回条数（默认 QueryCondition.DEFAULT_LIMIT = 4）
     */
    public List<SearchHit> search(String query, int limit) throws IOException {
        QueryCondition condition = new QueryCondition(query)
                .limit(limit > 0 ? limit : QueryCondition.DEFAULT_LIMIT);
        List<Document> docs = repository.search(condition);

        return docs.stream()
                .map(d -> new SearchHit(d.getId(), d.getScore(), d.getContent(), d.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * RAG 问答：检索增强生成
     * 检索 → 提示语增强（ofUserAugment）→ ChatModel 生成回答
     *
     * @param question 用户问题
     * @param limit    检索条数（默认 4）
     * @return AI 回答文本
     */
    public String ask(String question, int limit) throws IOException {
        List<Document> context = new ArrayList<>(
                repository.search(new QueryCondition(question)
                        .limit(limit > 0 ? limit : QueryCondition.DEFAULT_LIMIT)));

        log.info("[RAG Ask] question={} contextSize={}", question, context.size());

        ChatMessage augmented = ChatMessage.ofUserAugment(question, context);
        try {
            return chatModel.prompt(augmented).call().getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }
}
