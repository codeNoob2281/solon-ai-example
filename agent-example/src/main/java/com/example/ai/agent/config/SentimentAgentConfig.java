package com.example.ai.agent.config;

import com.example.ai.agent.dto.CommentSentimentResult;
import com.example.ai.core.LoggingChatInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleInterceptor;
import org.noear.solon.ai.agent.simple.SimpleTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 评论情感分析 SimpleAgent 装配
 * <p>
 * SimpleAgent（solon-ai-agent 4.1.0，@Preview API）：单次直接响应的简单智能体，
 * 具备指令增强（角色 + 指令 → SystemPrompt）、历史窗口管理、自动重试、
 * JSON 输出 Schema 强制约束等特性。
 * <ul>
 *   <li>{@code outputSchema(Class)}：由 DTO 生成 JSON Schema 注入 SystemPrompt，
 *       并自动开启 response_format=json_object（OpenAI 兼容方言）；</li>
 *   <li>{@code sessionWindowSize(0)}：无状态单次分析，不回溯历史会话；</li>
 *   <li>{@code temperature(0.1)}：分类任务降低随机性；</li>
 *   <li>{@code retryConfig(3, 1000)}：调用失败指数退避自动重试（框架默认即 3 次/1s）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SentimentAgentConfig {

    /**
     * 情感标签：正向
     */
    public static final String SENTIMENT_POS = "POS";
    /**
     * 情感标签：负向
     */
    public static final String SENTIMENT_NEG = "NEG";
    /**
     * 情感标签：中性
     */
    public static final String SENTIMENT_NTU = "NTU";
    /**
     * 情感标签：无法分析
     */
    public static final String SENTIMENT_UNKNOW = "UNKNOW";

    /**
     * 五维度情感分析指令（SimpleAgent 会组装为「## 你的角色 + ## 执行指令」SystemPrompt，
     * 输出 JSON Schema 由 outputSchema 自动追加）
     */
    private static final String SENTIMENT_INSTRUCTION = ""
            + "请对输入的电商平台用户评论，从以下五个维度逐一分析用户表达的情感倾向：\n"
            + "1. productAttributes（商品属性）：评论提及的商品功能、规格、材质、成分等属性是否符合预期或描述；\n"
            + "2. productQuality（商品质量）：做工、用料、耐用度、瑕疵、损坏等质量表现；\n"
            + "3. appearanceDesign（外观设计）：颜值、配色、造型、工艺、包装等外观与设计感受；\n"
            + "4. userExperience（使用体验）：上手难度、操作便捷性、舒适度等实际使用感受；\n"
            + "5. costPerformance（价格价值）：价格与价值是否匹配、性价比、优惠力度等感受。\n"
            + "\n"
            + "每个维度只能输出以下四个情感标签之一：\n"
            + "- POS：正向情感（满意、好评、赞扬、推荐）\n"
            + "- NEG：负向情感（不满、差评、抱怨、失望）\n"
            + "- NTU：中性情感（客观陈述、无明显褒贬）\n"
            + "- UNKNOW：无法分析（评论未提及该维度、语义模糊或无法判断）\n"
            + "\n"
            + "分析要求：\n"
            + "- 注意识别阴阳怪气、反讽、正话反说等表达（如「真是绝了，用两天就充不上电，质量好得很」）："
            + "先判断评论者的真实态度，再按真实情感倾向输出标签，不要被字面上的褒义词误导；\n"
            + "- 仅依据评论内容本身判断，不要臆测评论未表达的信息；\n"
            + "- 评论未提及的维度一律输出 UNKNOW；\n"
            + "- 严格按照指定的 JSON Schema 输出，不要输出任何额外解释文字。";

    @Bean
    public SimpleAgent commentSentimentAgent(ChatModel chatModel) {
        return SimpleAgent.of(chatModel)
                .name("comment_sentiment_agent")
                .role("电商平台用户评论情感分析助手")
                .instruction(SENTIMENT_INSTRUCTION)
                .outputSchema(CommentSentimentResult.class)
                .modelOptions(options -> options.temperature(0.1d))
                .retryConfig(3, 1000)
                .sessionWindowSize(0)
                .defaultInterceptorAdd(new LoggingInterceptor())
                .build();
    }

    private static class LoggingInterceptor extends LoggingChatInterceptor implements SimpleInterceptor {
    }
}
