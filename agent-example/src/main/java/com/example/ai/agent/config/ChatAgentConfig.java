package com.example.ai.agent.config;

import com.example.ai.agent.interceptor.ReActLoggingAgentInterceptor;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网页流式对话 ReActAgent 装配
 * <p>
 * 面向 Web 对话页的通用助手 Agent（无工具、无输出 Schema 约束）：
 * <ul>
 *   <li>{@code sessionWindowSize(20)}：多轮对话注入最近 20 条历史作为上下文；</li>
 *   <li>{@code temperature(0.7)}：对话场景保留适度随机性；</li>
 *   <li>{@code maxTurns(20)}：ReAct 循环上限（当前无工具，一般一轮即结束，留作后续扩展）。</li>
 * </ul>
 * 流式输出：{@code chatAgent.prompt(text).session(session).stream()} 返回
 * {@code Flux<AgentEvent>}（ReasonDeltaEvent 携带正文增量），由
 * {@code AiSdkStreamWrapper.toAiSdkAgentStream} 包装为 AI SDK 协议 SSE 推给前端。
 */
@Configuration
public class ChatAgentConfig {

    /**
     * 对话助手指令：多轮中文对话，理解上下文指代
     */
    private static final String CHAT_INSTRUCTION = ""
            + "你是网页对话助手，与用户进行多轮中文对话。\n"
            + "要求：\n"
            + "- 回答准确、简洁，复杂问题分点说明；\n"
            + "- 结合对话历史理解上下文（如「它」「那里」「刚才说的」等指代）；\n"
            + "- 不确定的信息如实说明，不要编造；\n"
            + "- 较长回复使用 Markdown 组织结构。";

    @Bean
    public ReActAgent chatAgent(ChatModel chatModel) {
        return ReActAgent.of(chatModel)
                .name("web_chat_agent")
                .role("通用对话助手")
                .instruction(CHAT_INSTRUCTION)
                .defaultInterceptorAdd(new ReActLoggingAgentInterceptor())
                .modelOptions(options -> options.temperature(0.7d))
                .retryConfig(3, 1000)
                .maxTurns(20)
                .sessionWindowSize(20)
                .build();
    }
}
