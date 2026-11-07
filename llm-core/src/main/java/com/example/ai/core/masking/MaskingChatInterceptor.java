package com.example.ai.core.masking;

import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.io.IOException;
import java.util.Map;

/**
 * 消息脱敏拦截器（阶段一：进模型侧掩码化闭环）
 * <p>
 * 覆盖三条链路：
 * <ol>
 *   <li><b>工具响应掩码化</b>（{@link #interceptTool} 后置）：工具输出明文 → 掩码占位符进会话；</li>
 *   <li><b>工具参数还原</b>（{@link #interceptTool} 前置）：模型引用的掩码 → 明文，真实写入磁盘；</li>
 *   <li><b>请求消息兜底掩码化</b>（{@link #interceptCall}/{@link #interceptStream} 前置）：
 *       历史 / 系统消息中的明文在发给模型前统一掩码。</li>
 * </ol>
 * <p>
 * 映射表按会话 id 服务端隔离存储（见 {@link MaskMapStore}），不进入 Prompt / toolContext、不下发客户端。
 * 还原失败 fail-closed：宁可拦截本次工具调用，也不把掩码字面量写进文件。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class MaskingChatInterceptor implements ChatInterceptor {

    private final SecretMasker masker;
    private final MaskMapStore mapStore;

    public MaskingChatInterceptor(SecretMasker masker, MaskMapStore mapStore) {
        this.masker = masker;
        this.mapStore = mapStore;
    }

    @Override
    public ToolResult interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        MaskMap map = mapFor(req);

        // 方向③：工具参数还原（前置）。与方向①必须同批生效，否则 write/edit 会写入掩码字面量
        if (req.getArgs() != null) {
            masker.unmaskArgs(req.getArgs(), map);
        }

        ToolResult result = chain.doIntercept(req);

        // 方向①：工具响应掩码化（后置，落库级：明文不进会话/上下文/observer）
        if (!ToolResult.isEmpty(result)) {
            return masker.maskToolResult(result, map);
        }
        return result;
    }

    @Override
    public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        // 方向②：请求消息掩码化（前置，兜底历史与系统消息）
        masker.maskPrompt(req.getFinalPrompt(), mapFor(req));
        return chain.doIntercept(req);
    }

    @Override
    public reactor.core.publisher.Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
        masker.maskPrompt(req.getFinalPrompt(), mapFor(req));
        return chain.doIntercept(req);
    }

    // ------------------------------------------------------------------
    // 会话 id 解析：请求侧与工具侧取到同一个 id

    private MaskMap mapFor(ChatRequest req) {
        return mapStore.get(resolveSessionId(req));
    }

    private MaskMap mapFor(ToolRequest req) {
        return mapStore.get(resolveSessionId(req));
    }

    private String resolveSessionId(ChatRequest req) {
        if (req == null) {
            return null;
        }
        // prepare 阶段会把 sessionId 写入 toolContext，请求侧据此取得会话 id
        if (req.getOptions() != null) {
            Object sessionId = req.getOptions().toolContext().get(ChatSession.ATTR_SESSIONID);
            if (sessionId != null) {
                return sessionId.toString();
            }
        }
        ChatSession session = req.getSession();
        return session == null ? null : session.getSessionId();
    }

    private String resolveSessionId(ToolRequest req) {
        if (req == null) {
            return null;
        }
        // ReAct Agent 工具链不传 ChatRequest（null），但 toolContext 中带有 sessionId
        Map<String, Object> toolsContext = req.getToolsContext();
        if (toolsContext != null) {
            Object sessionId = toolsContext.get(ChatSession.ATTR_SESSIONID);
            if (sessionId != null) {
                return sessionId.toString();
            }
        }
        return resolveSessionId(req.getRequest());
    }
}
