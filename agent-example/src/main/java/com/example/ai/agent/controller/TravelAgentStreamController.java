package com.example.ai.agent.controller;

import com.alibaba.fastjson2.JSON;
import com.example.ai.agent.dto.TravelStreamRequest;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.task.ActionEndEvent;
import org.noear.solon.ai.agent.react.task.ActionStartEvent;
import org.noear.solon.ai.agent.react.task.PlanEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonStartEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出行规划工作台流式端点（自定义 Agent 过程事件协议，供前端可视化工作过程）
 * <p>
 * 链路：{@code travelAgent.prompt(message).session(session).stream()} 产出
 * {@code Flux<AgentEvent>} → {@link #toPayloads} 逐事件映射为 JSON 事件帧 →
 * SSE 响应流式回传，前端据此渲染任务列表 / 思考链 / 工具调用 / 最终回答。
 * <p>
 * <b>为什么不用 AiSdkStreamWrapper（AI SDK 协议）</b>：AI SDK 包装器对 Agent 特有事件
 * 一律降级为 data-* part 且仅反射 getText()——{@code PlanEvent} 的任务列表内容
 * （{@code getPlans()}/{@code getPlanStage()}/{@code getPlanIndex()}）会丢失，
 * 无法支撑任务列表可视化；且本页需要按 ReAct 阶段（思考/行动/观察）组织过程时间线，
 * 自定义协议可以 1:1 透出全部事件语义。纯对话 demo（AgentChatController）继续使用
 * AI SDK 协议，两种集成方式在本模块内并存对照。
 * <p>
 * 事件帧契约（{@code data:<json>}，终帧为 [DONE] 哨兵）：
 * <pre>
 * {"type":"start","sessionId":"..."}                            首帧（回填会话 id）
 * {"type":"run_start"} / {"type":"run_end"}                     一次 ReAct 运行边界
 * {"type":"plan","stage":"CREATE|PROGRESS|REVISE","plans":[..],"planIndex":0}   任务列表（planningMode）
 * {"type":"reason_start"} / {"type":"reason_end","durationMs":..}               单轮推理边界
 * {"type":"text_start"} / {"type":"text_delta","text":".."} / {"type":"text_end"}   思考链/最终回答文本
 * {"type":"thinking_start|thinking_delta|thinking_end","text":".."}             模型原生深度思考（如有）
 * {"type":"action_start"} / {"type":"action_end"}               工具执行阶段边界
 * {"type":"tool_start","callId":"..","toolName":"..","args":{..}}               工具开始调用
 * {"type":"tool_end","callId":"..","toolName":"..","output":"..","durationMs":..[,"error":".."]}
 * {"type":"metrics","promptTokens":..,"completionTokens":..,"totalTokens":..,
 *  "cacheReadInputTokens":..,"cacheCreationInputTokens":..,"cacheRate":..,"durationMs":..,
 *  "contextTokens":..,"contextLength":..}                                       本轮度量（RunEndEvent.getMetrics）
 * {"type":"error","message":".."} / {"type":"finish"}           失败兜底 / 正常收尾
 * </pre>
 * <p>
 * SSE 管道与 {@link AgentChatController} 同源：ReAct 循环为阻塞执行，必须用
 * {@link SseEmitter} + 自主订阅（request(MAX)，事件到达即写即刷）而非返回 Flux
 * （后者按 request(1) 背压逐个拉取，会被阻塞的订阅线程饿死，前端无流式效果）；
 * 客户端断开 → onCompletion/onTimeout → dispose → 中断 Agent 执行。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/travel")
public class TravelAgentStreamController {

    /**
     * SSE 载荷的写出媒体类型（显式 UTF-8，避免 StringHttpMessageConverter 默认 ISO-8859-1 乱码）
     */
    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /**
     * 出行规划 ReActAgent（@Resource 按名称匹配 Bean travelAgent）
     */
    @Resource
    private ReActAgent travelAgent;

    /**
     * 共用的 ChatModel（llm-core 自动装配）：读取模型上下文窗口，作为用量面板占用率基准
     */
    @Resource
    private ChatModel chatModel;

    /**
     * 内存会话存储：会话 id -> AgentSession（demo 实现，进程内有效）
     */
    private final Map<String, AgentSession> sessionStore = new ConcurrentHashMap<>();

    /**
     * 会话记忆存储目录（相对路径，基于应用运行目录），可被 application.yml / 环境变量覆盖
     */
    @Value("${ai.chat.session.memory-dir:temp/chat-memory}")
    private String memoryDir;

    /**
     * 单轮流式请求的过程状态（Controller 为单例，状态必须每请求一份，不能挂在字段上）
     */
    private static final class StreamState {
        /**
         * 末次推理（ReasonEndEvent）的输入 token 数 ≈ 运行结束时的上下文占用
         */
        private long contextTokens;
    }

    /**
     * 出行规划工作台流式端点（自定义过程事件协议 SSE）
     *
     * @param request 会话 id + 一句话需求
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody TravelStreamRequest request) {
        // 无超时：生成时长不可预估，断连由 onCompletion/onTimeout 兜底
        SseEmitter emitter = new SseEmitter(0L);

        String sessionKey = trimToNull(request == null ? null : request.getId());
        String message = trimToNull(request == null ? null : request.getMessage());
        if (message == null) {
            sendPayload(emitter, "{\"type\":\"error\",\"message\":\"message 不能为空\"}");
            sendPayload(emitter, "{\"type\":\"finish\"}");
            sendPayload(emitter, "[DONE]");
            emitter.complete();
            return emitter;
        }

        AgentSession session = resolveSession(sessionKey);

        log.info("[Travel-Stream] sessionKey={}, prompt={}", sessionKey, abbreviate(message, 80));

        // 首帧：回填会话 id（多轮澄清续聊的关联键）
        Map<String, Object> startFrame = frame("start");
        startFrame.put("sessionId", session.getSessionId());

        // 每请求一份的度量状态（末次推理的上下文占用）
        StreamState state = new StreamState();

        Flux<String> payloads = Flux.concat(
                Flux.just(JSON.toJSONString(startFrame)),
                travelAgent.prompt(message).session(session).stream()
                        // 单事件 → 0..n 帧；concatMapIterable 保证严格按事件顺序出帧
                        .concatMapIterable(event -> toPayloads(event, state)),
                Flux.just("{\"type\":\"finish\"}", "[DONE]"));

        // 自主订阅：request(MAX)，事件到达即写即刷，无背压饥饿（缘由见类注释）
        Disposable disposable = payloads
                // ReAct 循环为阻塞执行（订阅时运行）：移出 Tomcat 工作线程
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        payload -> sendPayload(emitter, payload),
                        error -> {
                            log.error("[Travel-Stream] 流式过程异常: sessionKey={}", sessionKey, error);
                            Map<String, Object> errorFrame = frame("error");
                            errorFrame.put("message", error.getMessage() != null
                                    ? error.getMessage() : String.valueOf(error));
                            sendPayload(emitter, JSON.toJSONString(errorFrame));
                            sendPayload(emitter, "{\"type\":\"finish\"}");
                            sendPayload(emitter, "[DONE]");
                            emitter.complete();
                        },
                        emitter::complete);

        // 生命周期联动：客户端断开 / 超时 → 取消上游（sink.onCancel → 中断阻塞中的 Agent）
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);

        return emitter;
    }

    /**
     * 单个 Agent 事件 → 0..n 个 JSON 事件帧（顺序即语义顺序）
     *
     * @param state 本轮度量状态（记录末次推理的上下文占用）
     */
    private List<String> toPayloads(AgentEvent event, StreamState state) {
        if (event instanceof PlanEvent) {
            PlanEvent plan = (PlanEvent) event;
            Map<String, Object> frame = frame("plan");
            frame.put("stage", plan.getPlanStage().name());
            frame.put("plans", plan.getPlans());
            frame.put("planIndex", plan.getPlanIndex());
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (event instanceof ReasonDeltaEvent) {
            return toPayloads((ReasonDeltaEvent) event);
        }
        if (event instanceof ToolCallStartEvent) {
            ToolCallStartEvent tool = (ToolCallStartEvent) event;
            Map<String, Object> frame = frame("tool_start");
            frame.put("callId", tool.getCallId());
            frame.put("toolName", tool.getToolName());
            frame.put("args", tool.getArgs());
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (event instanceof ToolCallEndEvent) {
            ToolCallEndEvent tool = (ToolCallEndEvent) event;
            Map<String, Object> frame = frame("tool_end");
            frame.put("callId", tool.getCallId());
            frame.put("toolName", tool.getToolName());
            frame.put("output", tool.getText());
            frame.put("durationMs", tool.getDurationMs());
            if (tool.getError() != null) {
                frame.put("error", String.valueOf(tool.getError().getMessage()));
            }
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (event instanceof ReasonStartEvent) {
            return Collections.singletonList("{\"type\":\"reason_start\"}");
        }
        if (event instanceof ReasonEndEvent) {
            ReasonEndEvent reason = (ReasonEndEvent) event;
            // 记录本次推理的上下文占用（末次即运行结束时的上下文大小），供收尾的 metrics 帧使用
            AiUsage usage = reason.getResponse() == null ? null : reason.getResponse().getUsage();
            if (usage != null && usage.promptTokens() > 0) {
                state.contextTokens = usage.promptTokens();
            }
            Map<String, Object> frame = frame("reason_end");
            frame.put("durationMs", reason.getDurationMs());
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (event instanceof ActionStartEvent) {
            return Collections.singletonList("{\"type\":\"action_start\"}");
        }
        if (event instanceof ActionEndEvent) {
            return Collections.singletonList("{\"type\":\"action_end\"}");
        }
        if (event instanceof RunStartEvent) {
            return Collections.singletonList("{\"type\":\"run_start\"}");
        }
        if (event instanceof RunEndEvent) {
            RunEndEvent runEnd = (RunEndEvent) event;
            Map<String, Object> frame = frame("run_end");
            frame.put("turnCount", runEnd.getTrace().getTurnCount());
            return Arrays.asList(JSON.toJSONString(frame), metricsPayload(runEnd, state));
        }
        // 其余事件（HITL/Context/拦截器等）本页不消费，静默跳过
        return Collections.emptyList();
    }

    /**
     * 本轮度量帧：token 用量（输入/输出/缓存）+ 缓存命中率 + 上下文占用。
     *
     * <p>数据源为 {@link RunEndEvent#getMetrics()}（框架在一次运行开始时 reset、结束时写入总耗时，
     * 每次模型交互 addUsage 累加），因此是「本轮流式请求」的合计值；缓存命中率取
     * {@link Metrics#getCacheRate()}（缓存读取输入 token / 输入 token，0-100 保留两位小数）。</p>
     *
     * <p>上下文占用另有两个字段：{@code contextTokens} 为末次推理的输入 token（= 运行结束时
     * 上下文实际大小，provider 未回 usage 时退化为本轮输入合计），{@code contextLength} 为模型
     * 上下文窗口（ChatModel 配置项 ai.chat.openai.context-length），前端据此计算占用率。</p>
     */
    private String metricsPayload(RunEndEvent event, StreamState state) {
        Metrics metrics = event.getMetrics();
        Map<String, Object> frame = frame("metrics");
        frame.put("promptTokens", metrics.getPromptTokens());
        frame.put("completionTokens", metrics.getCompletionTokens());
        frame.put("totalTokens", metrics.getTotalTokens());
        frame.put("cacheReadInputTokens", metrics.getCacheReadInputTokens());
        frame.put("cacheCreationInputTokens", metrics.getCacheCreationInputTokens());
        frame.put("cacheRate", metrics.getCacheRate());
        frame.put("durationMs", metrics.getTotalDuration());
        frame.put("contextTokens",
                state.contextTokens > 0 ? state.contextTokens : metrics.getPromptTokens());
        frame.put("contextLength", chatModel.getConfig().getContextLength());
        return JSON.toJSONString(frame);
    }

    /**
     * 推理增量事件：内嵌 ChatEvent 按类型映射为思考链/最终回答文本帧。
     * 工具调用类 ChatEvent 不在此映射——工具执行统一由 ToolCallStart/End 事件出帧。
     */
    private List<String> toPayloads(ReasonDeltaEvent event) {
        ChatEvent chatEvent = event.getChatEvent();
        if (chatEvent == null) {
            return Collections.emptyList();
        }
        ChatEventType type = chatEvent.getType();
        if (type == ChatEventType.TEXT_DELTA) {
            String text = chatEvent.getTextOrEmpty();
            if (text.isEmpty()) {
                return Collections.emptyList();
            }
            Map<String, Object> frame = frame("text_delta");
            frame.put("text", text);
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (type == ChatEventType.TEXT_START) {
            return Collections.singletonList("{\"type\":\"text_start\"}");
        }
        if (type == ChatEventType.TEXT_END) {
            return Collections.singletonList("{\"type\":\"text_end\"}");
        }
        if (type == ChatEventType.THINKING_START) {
            return Collections.singletonList("{\"type\":\"thinking_start\"}");
        }
        if (type == ChatEventType.THINKING_DELTA) {
            String text = chatEvent.getTextOrEmpty();
            if (text.isEmpty()) {
                return Collections.emptyList();
            }
            Map<String, Object> frame = frame("thinking_delta");
            frame.put("text", text);
            return Collections.singletonList(JSON.toJSONString(frame));
        }
        if (type == ChatEventType.THINKING_END) {
            return Collections.singletonList("{\"type\":\"thinking_end\"}");
        }
        return Collections.emptyList();
    }

    /**
     * 写出一帧 SSE 事件（SseEmitter 按 "data:&lt;payload&gt;\n\n" 组帧并 flush）。
     * 写失败（如客户端断开）抛出触发 onError → complete → dispose → 中断 Agent。
     */
    private void sendPayload(SseEmitter emitter, String payload) {
        try {
            emitter.send(payload, TEXT_PLAIN_UTF8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (IllegalStateException ex) {
            // emitter 已 complete（如上游完成与断开竞争），静默忽略后续帧
            log.debug("[Travel-Stream] emitter 已关闭，丢弃事件: {}", ex.getMessage());
        }
    }

    /**
     * 解析会话：会话 id 命中存量会话则续用（多轮澄清）；未命中则新建
     */
    private AgentSession resolveSession(String sessionKey) {
        if (sessionKey != null) {
            AgentSession existing = sessionStore.get(sessionKey);
            if (existing != null) {
                return existing;
            }
            log.info("[Travel-Stream] 会话不存在或已失效，创建新会话: sessionKey={}", sessionKey);
        }

        if (sessionKey == null) {
            sessionKey = UUID.randomUUID().toString();
        }

        FileAgentSession session = new FileAgentSession(sessionKey, memoryDir);
        sessionStore.put(session.getSessionId(), session);
        return session;
    }

    private static Map<String, Object> frame(String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        return frame;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
