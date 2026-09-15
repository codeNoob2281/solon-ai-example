package com.example.ai.agent.controller;

import com.example.ai.agent.dto.CommentInput;
import com.example.ai.agent.dto.CommentSentimentResult;
import com.example.ai.agent.service.CommentSentimentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 评论情感分析接口（SimpleAgent 示例）
 * <pre>
 * POST /agent/sentiment/analyze   用户评论五维度情感分析
 * </pre>
 */
@Slf4j
@Tag(name = "评论情感分析", description = "基于 solon-ai-agent SimpleAgent 的电商用户评论五维度情感分析"
        + "（商品属性 / 商品质量 / 外观设计 / 使用体验 / 价格价值）")
@RestController
@RequestMapping("/agent/sentiment")
public class SentimentAnalysisController {

    @Resource
    private CommentSentimentService commentSentimentService;

    /**
     * 用户评论五维度情感分析：POST /agent/sentiment/analyze
     * <pre>{@code
     * 请求：{ "commentId": "c20260912001", "content": "手机外观很漂亮，电池不太耐用，这个价格还算划算。" }
     * 响应：{ "productAttributes": "UNKNOW", "productQuality": "NEG", "appearanceDesign": "POS",
     *        "userExperience": "NTU", "costPerformance": "POS" }
     * }</pre>
     */
    @Operation(summary = "用户评论五维度情感分析",
            description = "输入评论（commentId/content），SimpleAgent 从商品属性、商品质量、外观设计、使用体验、价格价值"
                    + "五个维度分析情感，每个维度返回 POS（正向）/ NEG（负向）/ NTU（中性）/ UNKNOW（无法分析）标签")
    @PostMapping("/analyze")
    public CommentSentimentResult analyze(@RequestBody CommentInput input) {
        log.info("[Sentiment] 收到分析请求: commentId={}", input == null ? null : input.getCommentId());
        return commentSentimentService.analyze(input);
    }
}
