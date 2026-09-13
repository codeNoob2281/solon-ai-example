package com.example.ai.agent.service;

import com.example.ai.agent.config.SentimentAgentConfig;
import com.example.ai.agent.dto.CommentInput;
import com.example.ai.agent.dto.CommentSentimentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 评论情感分析服务（SimpleAgent 单次调用）
 * <p>
 * 链路：评论 DTO 序列化为 JSON 用户提示词 → {@code SimpleAgent.prompt(...).call()}
 * →（框架组装 SystemPrompt：角色 + 五维度指令 + 输出 JSON Schema，自动重试）
 * → {@code SimpleResponse.toBean(CommentSentimentResult.class)} 解析结构化结果
 * → 情感标签归一化（非法/缺失值兜底为 UNKNOW）。
 */
@Slf4j
@Service
public class CommentSentimentService {

    /** 合法情感标签（超出该集合的模型输出一律归一化为 UNKNOW） */
    private static final Set<String> VALID_LABELS = new HashSet<>(Arrays.asList(
            SentimentAgentConfig.SENTIMENT_POS,
            SentimentAgentConfig.SENTIMENT_NEG,
            SentimentAgentConfig.SENTIMENT_NTU,
            SentimentAgentConfig.SENTIMENT_UNKNOW));

    private final SimpleAgent commentSentimentAgent;
    private final ObjectMapper objectMapper;

    public CommentSentimentService(SimpleAgent commentSentimentAgent, ObjectMapper objectMapper) {
        this.commentSentimentAgent = commentSentimentAgent;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析单条用户评论的五维度情感
     *
     * @param input 评论输入（commentId + content）
     * @return 五维度情感标签结果
     */
    public CommentSentimentResult analyze(CommentInput input) {
        if (input == null || input.getContent() == null || input.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("评论内容(content)不能为空");
        }

        String userPrompt = buildUserPrompt(input);
        try {
            SimpleResponse response = commentSentimentAgent.prompt(userPrompt).call();
            CommentSentimentResult raw = response.toBean(CommentSentimentResult.class);
            if (raw == null) {
                throw new IllegalStateException("情感分析结果为空");
            }

            CommentSentimentResult result = normalize(raw);
            log.debug("[Sentiment] 原始模型输出: {}", response.getContent());
            log.info("[Sentiment] commentId={}, result={}", input.getCommentId(), result);
            return result;
        } catch (Throwable e) {
            throw new IllegalStateException("评论情感分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建用户提示词：评论序列化为 JSON 传入（与需求定义的输入结构一致）
     */
    private String buildUserPrompt(CommentInput input) {
        try {
            return "待分析的用户评论：\n" + objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评论参数序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 情感标签归一化：trim + 大写 + 合法性校验，非法/缺失值兜底为 UNKNOW
     */
    private CommentSentimentResult normalize(CommentSentimentResult raw) {
        CommentSentimentResult result = new CommentSentimentResult();
        result.setProductAttributes(normalizeLabel(raw.getProductAttributes()));
        result.setProductQuality(normalizeLabel(raw.getProductQuality()));
        result.setAppearanceDesign(normalizeLabel(raw.getAppearanceDesign()));
        result.setUserExperience(normalizeLabel(raw.getUserExperience()));
        result.setCostPerformance(normalizeLabel(raw.getCostPerformance()));
        return result;
    }

    private String normalizeLabel(String label) {
        if (label == null) {
            return SentimentAgentConfig.SENTIMENT_UNKNOW;
        }
        String upper = label.trim().toUpperCase(Locale.ROOT);
        return VALID_LABELS.contains(upper) ? upper : SentimentAgentConfig.SENTIMENT_UNKNOW;
    }
}
