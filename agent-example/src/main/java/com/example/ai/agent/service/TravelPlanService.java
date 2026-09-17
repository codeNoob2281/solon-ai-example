package com.example.ai.agent.service;

import com.example.ai.agent.dto.TravelPlanRequest;
import com.example.ai.agent.dto.TravelPlanResponse;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出行规划服务（ReActAgent 示例，一句话输入 + Agent 层意图澄清）
 * <p>
 * 链路：
 * <ol>
 *   <li><b>会话管理</b>：按 sessionId 维持 {@link InMemoryAgentSession}（进程内），
 *       首次请求新建并回传 sessionId；多轮澄清时客户端回传 sessionId 续聊；</li>
 *   <li><b>一句话直传</b>：用户输入原文不做任何槽位校验，直接作为用户提示词交给
 *       {@code ReActAgent}——意图识别、要素澄清、（澄清通过后）计划拆解与工具调用
 *       全部由 Agent 完成；</li>
 *   <li><b>多轮澄清</b>：Agent 判定三要素（出发城市/目标城市/期望到达时刻）缺失时
 *       以自然语言向用户追问，历史上下文由 sessionWindowSize 注入工作记忆；</li>
 *   <li><b>自然语言透出</b>：Agent 响应格式不做限制（自然语言，可为 Markdown 格式），
 *       服务层不做任何结构化解析，原样透出并仅回填 sessionId。</li>
 * </ol>
 */
@Slf4j
@Service
public class TravelPlanService {

    /**
     * 出行规划 ReActAgent（@Resource 按名称匹配 Bean travelAgent）
     */
    @Resource
    private ReActAgent travelAgent;

    /**
     * 内存会话存储：sessionId -> AgentSession（demo 实现，进程内有效）
     */
    private final Map<String, AgentSession> sessionStore = new ConcurrentHashMap<>();

    /**
     * 出行规划：一句话输入 → Agent 意图识别/澄清/规划
     *
     * @param request 一句话需求 + 可选 sessionId（多轮澄清续聊）
     * @return Agent 自然语言回复（可为 Markdown，澄清追问与规划建议均为该形态），sessionId 供续聊
     */
    public TravelPlanResponse plan(TravelPlanRequest request) {
        TravelPlanRequest input = request == null ? new TravelPlanRequest() : request;
        String message = trimToNull(input.getMessage());
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空：请用一句话描述出行需求");
        }

        // 1. 会话解析：sessionId 有效则续用（多轮澄清），否则新建
        AgentSession session = resolveSession(trimToNull(input.getSessionId()));

        // 2. 一句话原文直传 Agent（意图识别/澄清/规划全部在 Agent 层完成）
        String content;
        try {
            ReActResponse response = travelAgent.prompt(message).session(session).call();
            content = response.getContent();
        } catch (Throwable e) {
            // AgentRequest.call() 签名 throws Throwable，必须捕获 Throwable
            throw new IllegalStateException("出行规划失败: " + e.getMessage(), e);
        }

        // 3. 自然语言原样透出（不解析、不校验结构），仅回填 sessionId
        log.info("[Travel] Agent 已回复: sessionId={}, contentLength={}",
                session.getSessionId(), content == null ? 0 : content.length());
        TravelPlanResponse result = new TravelPlanResponse();
        result.setSessionId(session.getSessionId());
        result.setContent(content);
        return result;
    }

    /**
     * 解析会话：sessionId 命中存量会话则续用；未命中（过期/伪造）则新建并告警
     */
    private AgentSession resolveSession(String sessionId) {
        if (sessionId != null) {
            AgentSession existing = sessionStore.get(sessionId);
            if (existing != null) {
                return existing;
            }
            log.warn("[Travel] 会话不存在或已失效，创建新会话: sessionId={}", sessionId);
        }
        InMemoryAgentSession session = InMemoryAgentSession.of(UUID.randomUUID().toString());
        sessionStore.put(session.getSessionId(), session);
        return session;
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
