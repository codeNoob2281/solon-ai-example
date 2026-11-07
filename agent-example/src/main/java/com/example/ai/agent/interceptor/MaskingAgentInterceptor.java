package com.example.ai.agent.interceptor;

import com.example.ai.core.masking.MaskingChatInterceptor;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.tool.ToolResult;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * ReAct Agent 消息脱敏适配器
 * <p>
 * 背景：ChatModel 默认 {@code ChatInterceptor} 只覆盖「模型自动工具调用」链路；
 * ReAct Agent 的工具执行走 {@code trace.getOptions().getInterceptors()} 组建的 ToolChain，
 * 只会调用 Agent 级拦截器。因此在这里把 llm-core 的 {@link MaskingChatInterceptor} 适配为
 * {@link AbsReActInterceptor}，一并接管：工具响应掩码化、工具参数还原、请求消息兜底掩码。
 * <p>
 * 工具链执行时 {@code ToolRequest#getRequest()} 为 null（ActionTask 传入 null），
 * 映射表改由 toolContext 中的 sessionId 解析，跨步骤仍保持一致。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class MaskingAgentInterceptor extends AbsReActInterceptor {

    private final MaskingChatInterceptor delegate;

    public MaskingAgentInterceptor(MaskingChatInterceptor delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        return delegate.interceptCall(req, chain);
    }

    @Override
    public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
        return delegate.interceptStream(req, chain);
    }

    @Override
    public ToolResult interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        return delegate.interceptTool(req, chain);
    }
}
