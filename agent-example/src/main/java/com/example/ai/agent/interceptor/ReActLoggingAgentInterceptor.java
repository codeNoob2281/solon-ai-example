package com.example.ai.agent.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.ChatMessage;

/**
 * ReAct Agent 日志拦截器（出行规划示例）
 * <p>
 * 记录智能体起止、每次工具调用耗时与最终回合/工具调用统计，
 * 便于直观观察 ReAct（Reason -> Action -> Observation）循环的执行轨迹。
 */
@Slf4j
public class ReActLoggingAgentInterceptor extends AbsReActInterceptor {

    @Override
    public void onAgentStart(ReActTrace trace) {
        log.info("[AgentLogger] 开始执行: agent={}, prompt={}",
                trace.getAgentName(),
                trace.getOriginalPrompt() == null ? null : trace.getOriginalPrompt().getUserContent());
    }

    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger toolExchanger,
                              ChatMessage observation, Throwable error, long durationMs) {
        if (error != null) {
            log.warn("[AgentLogger] 工具调用失败: name={}, args={}, duration={}ms, error={}",
                    toolExchanger.getToolName(), toolExchanger.getArgs(), durationMs, error.getMessage());
        } else {
            log.info("[AgentLogger] 工具调用完成: name={}, duration={}ms",
                    toolExchanger.getToolName(), durationMs);
        }
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        log.info("[AgentLogger] 执行结束: turns={}, toolCalls={}, plans={}, abnormal={}",
                trace.getTurnCount(), trace.getToolCallCount(),
                trace.getPlans().size(), trace.isAbnormal());
    }
}
