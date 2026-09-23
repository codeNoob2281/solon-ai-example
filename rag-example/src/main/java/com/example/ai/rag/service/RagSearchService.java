package com.example.ai.rag.service;

import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.retrieval.RagRetrievalStrategy;
import com.example.ai.rag.retrieval.RagRetrievalStrategyResolver;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * RAG 检索与同步问答服务（编排层）
 * <p>
 * 检索实现已抽离为可配置策略 {@link RagRetrievalStrategy}：本服务只负责编排
 * 「策略检索 → 提示词组装 → ChatModel 生成」，不关心检索的具体实现。
 * 策略通过 {@code ai.rag.search.strategy}（环境变量 {@code RAG_SEARCH_STRATEGY}）
 * 配置选择，默认 {@code parent-child}——即父子分段 / Small-to-Big 检索
 * （子块向量检索 → 父块映射，实现见 {@code ParentChildRetrievalStrategy}）。
 * <p>
 * 问答使用 llm-core 自动装配的全局 ChatModel：系统提示词（智能客服）+ 自定义用户提示词模版
 * 由 {@link RagPromptFactory} 承载（与流式问答 {@link RagChatService} 共用）。
 */
@Slf4j
@Service
public class RagSearchService {

    private final RagRetrievalStrategy retrievalStrategy;
    private final ChatModel chatModel;
    private final RagPromptFactory promptFactory;

    public RagSearchService(RagRetrievalStrategyResolver retrievalResolver,
                            ChatModel chatModel,
                            RagPromptFactory promptFactory) {
        // 启动时按配置选定策略（配置错误在此快速失败）
        this.retrievalStrategy = retrievalResolver.resolve();
        this.chatModel = chatModel;
        this.promptFactory = promptFactory;
    }

    /**
     * 检索：委托给当前配置的检索策略
     * （默认策略返回与 query 最相关的父块，内容完整，供上层浏览/验证入库效果）
     *
     * @param query               查询文本
     * @param limit               检索条数上限（&lt;=0 时用策略默认值）
     * @param similarityThreshold 相似度阈值（可选；null 或非正数时用策略默认值）
     */
    public List<SearchHit> search(String query, int limit, Double similarityThreshold) throws IOException {
        List<SearchHit> hits = retrievalStrategy.retrieve(query, limit, similarityThreshold);
        log.info("[RAG Search] strategy={} query={} hits={}", retrievalStrategy.name(), query, hits.size());
        return hits;
    }

    /**
     * RAG 问答（同步）：检索增强生成
     * 策略检索 → 命中内容进 LLM 上下文 → 系统提示词（智能客服）
     * + 自定义用户提示词模版 → ChatModel 生成回答
     *
     * @param question            用户问题
     * @param limit               检索条数上限（&lt;=0 时用策略默认值）
     * @param similarityThreshold 相似度阈值（可选；null 或非正数时用策略默认值）
     * @return AI 回答文本
     */
    public String ask(String question, int limit, Double similarityThreshold) throws IOException {
        List<SearchHit> context = retrievalStrategy.retrieve(question, limit, similarityThreshold);

        log.info("[RAG Ask] question={} strategy={} contextSize={}",
                question, retrievalStrategy.name(), context.size());

        ChatMessage user = promptFactory.renderUserMessage(question, context);
        try {
            // 系统提示词走 systemPrompt()，由框架统一组装为 System Message
            return chatModel.prompt(user)
                    .systemPrompt(promptFactory.systemPrompt())
                    .call()
                    .getContent();
        } catch (Exception e) {
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }
}
