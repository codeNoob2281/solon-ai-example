package com.example.ai.agent.controller;

import com.example.ai.agent.dto.ChatStreamRequest;
import com.example.ai.core.ChatModelResolver;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.ui.aisdk.AiSdkStreamWrapper;
import org.noear.solon.web.sse.SseEvent;
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
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Agent 流式对话端点（Vercel AI SDK UI Message Stream Protocol v1）
 * <p>
 * 链路：{@code @ai-sdk/react useChat}（DefaultChatTransport，POST 消息历史）
 * → 本端点解析最后一条用户消息 → {@code chatAgent.prompt(text).session(session).stream()}
 * 产出 {@code Flux<AgentEvent>} → {@link AiSdkStreamWrapper#toAiSdkAgentStream}
 * 包装为 AI SDK 协议事件（start / text-start / text-delta / text-end / finish / [DONE]）
 * → SSE 响应流式回传，前端逐字渲染。
 * <p>
 * 关键点：
 * <ul>
 *   <li>必须携带响应头 {@code x-vercel-ai-ui-message-stream: v1}（AI SDK 客户端按协议版本解析）；</li>
 *   <li><b>用 {@link SseEmitter} + 自主订阅而非返回 {@code Flux}</b>：ReAct 循环是阻塞执行，
 *       会占住订阅线程整个生成期；若返回 Flux，Spring 的 SseEmitterSubscriber 按 request(1)
 *       逐个拉取，其请求信号经 {@code subscribeOn} 调度后会排在被阻塞的线程后面，
 *       事件全部积压到生成结束才一次性刷出（前端无流式效果）。自主订阅按 request(MAX) 消费，
 *       事件到达即 {@code emitter.send}（内部逐帧 write+flush）；</li>
 *   <li>Wrapper 产出的 {@link SseEvent} 只暴露 toString()（完整 SSE 报文 "data:&lt;payload&gt;\n"），
 *       此处剥离帧前缀后交由 SseEmitter 按标准 SSE 重新组帧（payload 中无换行，安全）；</li>
 *   <li>阻塞的 ReAct 循环经 {@code subscribeOn(boundedElastic)} 移出 Tomcat 工作线程；
 *       客户端断开（或点「停止」）→ onCompletion/onTimeout → dispose → 中断 Agent 执行；</li>
 *   <li>多轮会话：按请求体 id（前端 useChat 的 chatId）维持 {@link InMemoryAgentSession}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    /**
     * AI SDK 协议版本响应头（UI Message Stream Protocol v1）
     */
    private static final String UI_MESSAGE_STREAM_HEADER = "x-vercel-ai-ui-message-stream";
    private static final String UI_MESSAGE_STREAM_VERSION = "v1";

    /**
     * SSE 载荷的写出媒体类型（显式 UTF-8，避免 StringHttpMessageConverter 默认 ISO-8859-1 乱码）
     */
    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /**
     * AI SDK 协议包装器（可复用实例；每次调用内部独立生成 messageId/partId）
     */
    private final AiSdkStreamWrapper aiSdkWrapper = AiSdkStreamWrapper.of();

    /**
     * 网页对话 ReActAgent（@Resource 按名称匹配 Bean chatAgent）
     */
    @Resource
    private ReActAgent chatAgent;

    /**
     * 模型解析器（llm-core 契约；model-example 提供实现，未引入时为空 → 只走默认模型）
     */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<ChatModelResolver> chatModelResolverProvider;

    /**
     * 内存会话存储：chatId -> AgentSession（demo 实现，进程内有效）
     */
    private final Map<String, AgentSession> sessionStore = new ConcurrentHashMap<>();

    /**
     * 流式对话端点（AI SDK 协议 SSE）
     *
     * @param request  useChat 请求体（完整消息历史）
     * @param response 用于写入协议版本响应头（须在流提交前设置）
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody ChatStreamRequest request, HttpServletResponse response) {
        response.setHeader(UI_MESSAGE_STREAM_HEADER, UI_MESSAGE_STREAM_VERSION);

        // 无超时：生成时长不可预估，断连由 onCompletion/onTimeout 兜底
        SseEmitter emitter = new SseEmitter(0L);

        String chatId = trimToNull(request == null ? null : request.getId());
        String userText = request == null ? null : request.lastUserText();
        if (userText == null) {
            // 空输入：按 AI SDK 协议错误形态回包（error part + finish part + [DONE]），前端 useChat 可感知
            sendPayload(emitter, "{\"type\":\"error\",\"errorText\":\"消息内容不能为空\"}");
            sendPayload(emitter, "{\"type\":\"finish\",\"finishReason\":\"error\"}");
            sendPayload(emitter, "[DONE]");
            emitter.complete();
            return emitter;
        }

        AgentSession session = resolveSession(chatId);
        Map<String, Object> metadata = chatId == null
                ? null
                : Collections.singletonMap("chatId", chatId);

        log.info("[Chat] chatId={}, modelId={}, reasoningLevel={}, prompt={}",
                chatId, request.getModelId(), request.getReasoningLevel(), abbreviate(userText, 80));

        // 模型切换：未指定 modelId → 直接用默认 ChatModel（.env 装配，即聊天页「默认」）；
        // 指定则按模型配置解析，解析失败按协议错误帧回包（此时继续用默认模型会造成静默串模型）
        ChatModelResolver.ResolvedChatModel resolved = resolveChatModel(request);
        if (resolved == null && trimToNull(request.getModelId()) != null) {
            sendPayload(emitter, "{\"type\":\"error\",\"errorText\":\"所选模型不可用（可能已被删除或禁用），请重新选择\"}");
            sendPayload(emitter, "{\"type\":\"finish\",\"finishReason\":\"error\"}");
            sendPayload(emitter, "[DONE]");
            emitter.complete();
            return emitter;
        }

        Consumer<ReActOptionsAmend> optionsAdjustor = buildOptionsAdjustor(resolved);
        Flux<AgentEvent> agentEvents = chatAgent.prompt(userText).session(session)
                .options(optionsAdjustor).stream();
        Flux<SseEvent> sseEvents = aiSdkWrapper.toAiSdkAgentStream(agentEvents, metadata);

        // 自主订阅：request(MAX)，事件到达即写即刷，无背压饥饿
        Disposable disposable = sseEvents
                // ReAct 循环为阻塞执行（订阅时运行）：移出 Tomcat 工作线程
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> sendPayload(emitter, toPayload(event)),
                        error -> {
                            log.error("[Chat] 流式对话异常: chatId={}", chatId, error);
                            emitter.completeWithError(error);
                        },
                        emitter::complete);

        // 生命周期联动：客户端断开 / 超时 → 取消上游（sink.onCancel → 中断阻塞中的 Agent）
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);

        return emitter;
    }

    /**
     * 解析本次请求使用的模型
     * <ul>
     *   <li>未指定 modelId：不需要覆盖模型，返回 null（继续走默认 ChatModel）；</li>
     *   <li>指定 modelId：由 {@link ChatModelResolver} 解析（modelId 非法 / 模型禁用 → null，
     *       由调用方按协议错误帧回包）；</li>
     *   <li>未引入模型配置模块（无 {@link ChatModelResolver} Bean）时按「模型不可用」处理。</li>
     * </ul>
     */
    private ChatModelResolver.ResolvedChatModel resolveChatModel(ChatStreamRequest request) {
        String modelId = trimToNull(request.getModelId());
        if (modelId == null) {
            return null;
        }
        ChatModelResolver resolver = chatModelResolverProvider.getIfAvailable();
        if (resolver == null) {
            log.warn("[Chat] 未装配 ChatModelResolver（model-example），无法切换模型: modelId={}", modelId);
            return null;
        }
        return resolver.resolve(modelId, trimToNull(request.getReasoningLevel()));
    }

    /**
     * 构造本次请求的选项修正器（每次都返回非 null，用于把上一轮的模型 / 推理参数清干净）
     * <p>
     * 背景：{@code ReActRequest.init()} 在续接同一 {@code AgentSession} 时会从上轮 trace
     * <b>拷贝</b> options，上轮设置的 chatModel / reasoning_effort 会残留。因此这里对每次请求都显式
     * 覆盖全部三项，避免「这一轮选默认，却沿用了上一轮选的模型」这类串档问题。
     * <ul>
     *   <li>chatModel：命中模型配置 → 用该模型；否则回落 Agent 默认模型（.env 装配）；</li>
     *   <li>推理档位 apiValue 为空 → 移除 thinking / reasoning_effort（保持供应商默认行为）；</li>
     *   <li>apiValue 为 {@code off} → 关闭思考（见下方「双重下发」说明）；</li>
     *   <li>其余 → 作为 {@code reasoning_effort} 下发（具体写出的字段由 Solon AI 按模型归一化）。</li>
     * </ul>
     * <p>
     * 「关闭」为何要下发两次：{@code thinking(false)} 由 Solon AI 按<b>已知模型族</b>
     * （qwen/deepseek/glm/kimi/minimax 等）映射成 {@code enable_thinking} / {@code thinking.type}；
     * 未知模型（如 hy4-preview）会走 {@code ThinkingSwitchWire.NONE} 分支静默不写出，
     * 思考照样吃满额度导致正文为空。因此额外用 {@code optionSet} 透传
     * {@code enable_thinking=false} 作为兜底（忽略未知字段的网关无副作用）。
     */
    private Consumer<ReActOptionsAmend> buildOptionsAdjustor(ChatModelResolver.ResolvedChatModel resolved) {
        String reasoningValue = trimToNull(resolved == null ? null : resolved.getReasoningApiValue());
        ChatModel selected = resolved == null ? null : resolved.getChatModel();
        ChatModel fallback = chatAgent.getModel();
        boolean disableThinking = reasoningValue != null && "off".equalsIgnoreCase(reasoningValue);

        return adjustor -> {
            ChatModel target = selected != null ? selected : fallback;
            if (target != null) {
                adjustor.chatModel(target);
            }
            // 先清空上轮残留，再按本轮档位写入
            adjustor.thinking(null);
            adjustor.reasoning_effort(null);
            adjustor.optionRemove("enable_thinking");
            if (disableThinking) {
                adjustor.thinking(false);
                adjustor.optionSet("enable_thinking", false);
            } else if (reasoningValue != null) {
                adjustor.reasoning_effort(reasoningValue);
            }
        };
    }

    /**
     * 写出一帧 SSE 事件（SseEmitter 按 "data:&lt;payload&gt;\n\n" 组帧并 flush）。
     * 写失败（如客户端断开）抛出触发 onError → completeWithError → dispose → 中断 Agent。
     */
    private void sendPayload(SseEmitter emitter, String payload) {
        try {
            emitter.send(payload, TEXT_PLAIN_UTF8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (IllegalStateException ex) {
            // emitter 已 complete（如上游完成与断开竞争），静默忽略后续帧
            log.debug("[Chat] emitter 已关闭，丢弃事件: {}", ex.getMessage());
        }
    }

    /**
     * 解析会话：chatId 命中存量会话则续用（多轮上下文）；未命中则新建
     */
    private AgentSession resolveSession(String chatId) {
        if (chatId != null) {
            AgentSession existing = sessionStore.get(chatId);
            if (existing != null) {
                return existing;
            }
            log.info("[Chat] 会话不存在或已失效，创建新会话: chatId={}", chatId);
        }
        InMemoryAgentSession session = InMemoryAgentSession.of(
                chatId != null ? chatId : UUID.randomUUID().toString());
        sessionStore.put(session.getSessionId(), session);
        return session;
    }

    /**
     * SseEvent 只暴露 toString()（完整 SSE 报文 "data:&lt;payload&gt;\n"），
     * 剥离帧前缀与结尾换行，交由 SseEmitter 按标准 SSE 重新组帧输出
     */
    private static String toPayload(SseEvent event) {
        String frame = event.toString();
        if (frame.startsWith("data:")) {
            frame = frame.substring("data:".length());
        }
        int end = frame.length();
        while (end > 0 && frame.charAt(end - 1) == '\n') {
            end--;
        }
        return frame.substring(0, end);
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
