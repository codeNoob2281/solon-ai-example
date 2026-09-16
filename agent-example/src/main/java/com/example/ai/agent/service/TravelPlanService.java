package com.example.ai.agent.service;

import com.alibaba.fastjson2.JSON;
import com.example.ai.agent.dto.TravelPlan;
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
 *   <li><b>多轮澄清</b>：Agent 判定三要素（出发城市/目标城市/期望到达时刻）缺失时输出澄清 JSON，
 *       服务层识别后原样透出追问话术；历史上下文由 sessionWindowSize 注入工作记忆；</li>
 *   <li><b>结构化解析</b>：Agent 最终输出契约即 {@link TravelPlanResponse}（不含 sessionId），
 *       容错剥离 markdown 代码块后用 fastjson2 解析。</li>
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
     * @return needClarification=true 时为 Agent 追问话术，否则 plan 为结构化规划结果
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
        TravelPlanResponse result;
        try {
            ReActResponse response = travelAgent.prompt(message).session(session).call();
            result = parseAgentOutput(response.getContent());
        } catch (Throwable e) {
            // AgentRequest.call() 签名 throws Throwable，必须捕获 Throwable
            throw new IllegalStateException("出行规划失败: " + e.getMessage(), e);
        }

        // 3. 结果归一化：澄清态补齐追问话术，规划态校验结构
        if (Boolean.TRUE.equals(result.getNeedClarification())) {
            if (isBlank(result.getClarificationMessage())) {
                result.setClarificationMessage("请补充出行信息：出发城市、目标城市、期望到达时间。");
            }
            result.setPlan(null);
            log.info("[Travel] Agent 发起澄清: sessionId={}, missing={}, message={}",
                    session.getSessionId(), result.getMissingFields(), message);
        } else {
            TravelPlan plan = result.getPlan();
            if (plan == null || isBlank(plan.getRecommendedType())) {
                throw new IllegalStateException("模型未返回有效的出行规划结果");
            }
            log.info("[Travel] 规划完成: sessionId={}, {} -> {}, recommend={}",
                    session.getSessionId(), plan.getDepartureCity(), plan.getTargetCity(),
                    plan.getRecommendedType());
        }

        result.setSessionId(session.getSessionId());
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

    /**
     * 解析 Agent 最终输出：容错剥离 markdown 代码块围栏后按响应 DTO 解析
     * （Agent 输出契约即 TravelPlanResponse 结构，sessionId 由服务层回填）
     */
    private TravelPlanResponse parseAgentOutput(String rawContent) {
        String json = stripCodeFence(rawContent);
        TravelPlanResponse result;
        try {
            result = JSON.parseObject(json, TravelPlanResponse.class);
        } catch (Exception e) {
            log.warn("[Travel] 模型输出无法解析为结构化结果: {}", rawContent);
            throw new IllegalStateException("模型输出解析失败", e);
        }
        if (result == null || (result.getNeedClarification() == null && result.getPlan() == null)) {
            log.warn("[Travel] 模型输出缺少有效字段: {}", rawContent);
            throw new IllegalStateException("模型未返回有效的出行规划结果");
        }
        return result;
    }

    /**
     * 剥离模型可能添加的 markdown 代码块围栏（```json ... ```）
     */
    private String stripCodeFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineBreak > 0 && lastFence > firstLineBreak) {
                trimmed = trimmed.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
