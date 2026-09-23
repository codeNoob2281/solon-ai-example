package com.example.ai.rag.service;

import com.example.ai.rag.dto.KbSourceItem;
import com.example.ai.rag.dto.SearchHit;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.retrieval.RagRetrievalStrategy;
import com.example.ai.rag.retrieval.RagRetrievalStrategyResolver;
import com.example.ai.rag.storage.KnowledgeBaseStore;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * RAG 流式问答服务（编排层）
 * <p>
 * 链路：策略检索（每问独立检索，无会话记忆）→ 来源解析（documentId 去重 → 批查
 * kb_document → label=basename(source)，回退 title，再兜底"未命名文档"）→
 * ChatModel 流式生成（{@code Flux<ChatEvent>}，提示词与同步问答共用
 * {@link RagPromptFactory}）。
 * <p>
 * 检索发生在模型流开始之前：来源列表在流起始即确定，由控制器随 AI SDK 协议的
 * metadata part 一次下发（前端 message.metadata.sources）；检索为空时
 * {@code sources = []}，提示词中知识库结果为空串，模型按系统提示词兜底回答。
 */
@Slf4j
@Service
public class RagChatService {

    /** 检索条数上限（与前端展示的来源数上限一致；相似度阈值用策略默认 0.4） */
    private static final int RETRIEVAL_LIMIT = 4;

    private final RagRetrievalStrategy retrievalStrategy;
    private final ChatModel chatModel;
    private final RagPromptFactory promptFactory;
    private final KnowledgeBaseStore knowledgeBaseStore;

    public RagChatService(RagRetrievalStrategyResolver retrievalResolver,
                          ChatModel chatModel,
                          RagPromptFactory promptFactory,
                          KnowledgeBaseStore knowledgeBaseStore) {
        // 启动时按配置选定策略（配置错误在此快速失败）
        this.retrievalStrategy = retrievalResolver.resolve();
        this.chatModel = chatModel;
        this.promptFactory = promptFactory;
        this.knowledgeBaseStore = knowledgeBaseStore;
    }

    /**
     * 启动一轮流式问答：先同步完成检索与来源解析（决定 metadata），再返回模型事件流。
     * <p>
     * 检索失败（Milvus/嵌入服务不可用等）在本方法内抛出，由控制器以 AI SDK 协议
     * 错误帧回包；进入事件流后由 {@code AiSdkStreamWrapper} 负责错误收口。
     *
     * @param question 用户问题（最后一条用户消息文本）
     */
    public RagChatStream startStream(String question) throws IOException {
        List<SearchHit> hits = retrievalStrategy.retrieve(question, RETRIEVAL_LIMIT, null);
        List<KbSourceItem> sources = resolveSources(hits);
        log.info("[RAG Chat] question={} strategy={} hits={} sources={}",
                abbreviate(question, 80), retrievalStrategy.name(), hits.size(), sources.size());

        ChatMessage user = promptFactory.renderUserMessage(question, hits);
        // 系统提示词走 systemPrompt()，由框架统一组装为 System Message
        Flux<ChatEvent> events = chatModel.prompt(user)
                .systemPrompt(promptFactory.systemPrompt())
                .stream();
        return new RagChatStream(events, sources);
    }

    /**
     * 来源解析：按命中顺序收集 documentId 去重 → 按 id 批查 kb_document → 组装展示项。
     * 文档行缺失（如刚被删除）的命中不展示。
     */
    private List<KbSourceItem> resolveSources(List<SearchHit> hits) {
        LinkedHashSet<String> documentIds = new LinkedHashSet<>();
        for (SearchHit hit : hits) {
            String documentId = hit.getDocumentId();
            if (!hasText(documentId) && hit.getMetadata() != null) {
                Object value = hit.getMetadata().get(RagIngestionService.META_DOCUMENT_ID);
                documentId = value == null ? null : value.toString();
            }
            if (hasText(documentId)) {
                documentIds.add(documentId.trim());
            }
        }
        if (documentIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, KbDocument> docsById = new HashMap<>();
        for (KbDocument doc : knowledgeBaseStore.findDocumentsByIds(documentIds)) {
            docsById.put(doc.getId(), doc);
        }

        List<KbSourceItem> sources = new ArrayList<>(documentIds.size());
        for (String documentId : documentIds) {
            KbDocument doc = docsById.get(documentId);
            if (doc == null) {
                continue;
            }
            sources.add(new KbSourceItem(doc.getId(), sourceLabel(doc), doc.getTitle()));
        }
        return sources;
    }

    /**
     * 来源展示标签：basename(source)（支持 / 与 \ 分隔）→ title → "未命名文档"
     */
    private String sourceLabel(KbDocument doc) {
        String label = basename(doc.getSource());
        if (hasText(label)) {
            return label;
        }
        if (hasText(doc.getTitle())) {
            return doc.getTitle().trim();
        }
        return "未命名文档";
    }

    private String basename(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 && slash < trimmed.length() - 1
                ? trimmed.substring(slash + 1)
                : trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 流式问答启动结果：模型事件流 + 流开始前已确定的来源列表
     * （来源随 AI SDK 协议 metadata part 下发，非流内动态产生）
     */
    @Getter
    public static final class RagChatStream {
        private final Flux<ChatEvent> events;
        private final List<KbSourceItem> sources;

        RagChatStream(Flux<ChatEvent> events, List<KbSourceItem> sources) {
            this.events = events;
            this.sources = sources;
        }
    }
}
