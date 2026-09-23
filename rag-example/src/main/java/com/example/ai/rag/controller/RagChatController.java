package com.example.ai.rag.controller;

import com.example.ai.rag.dto.RagChatStreamRequest;
import com.example.ai.rag.service.RagChatService;
import lombok.extern.slf4j.Slf4j;
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

/**
 * RAG 流式问答端点（Vercel AI SDK UI Message Stream Protocol v1）
 * <p>
 * 链路（照搬 AgentChatController 模式，仅编排内容不同）：
 * {@code @ai-sdk/react useChat}（DefaultChatTransport，POST 消息历史）
 * → 本端点解析最后一条用户消息 → {@link RagChatService#startStream}
 * （检索 → 来源解析 → ChatModel 流式生成）→ {@link AiSdkStreamWrapper#toAiSdkStream}
 * 包装为 AI SDK 协议事件（start → metadata(sources) → reasoning/text 增量 → finish → [DONE]）
 * → SSE 响应流式回传，前端逐字渲染 Markdown。
 * <p>
 * 关键点：
 * <ul>
 *   <li>必须携带响应头 {@code x-vercel-ai-ui-message-stream: v1}（AI SDK 客户端按协议版本解析）；</li>
 *   <li><b>用 {@link SseEmitter} + 自主订阅而非返回 {@code Flux}</b>：模型流经
 *       {@code subscribeOn(boundedElastic)} 调度，若返回 Flux，Spring 的
 *       SseEmitterSubscriber 按 request(1) 逐个拉取，事件可能积压；自主订阅按
 *       request(MAX) 消费，事件到达即 {@code emitter.send}（内部逐帧 write+flush）；</li>
 *   <li>来源列表随 <b>metadata part</b>（协议原生字段，紧随 start part 下发）一次送达，
 *       前端直接读 {@code message.metadata.sources}，无需解析自定义 data part；</li>
 *   <li>THINKING_* 事件（推理模型如 deepseek-r1/qwen3 thinking 产生）自动映射为
 *       reasoning part；非思考模型无该 part，前端 Reasoning 组件自然不渲染；</li>
 *   <li>无会话/记忆：RAG 问答每问独立检索，前端消息历史仅作 UI 展示。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/chat")
public class RagChatController {

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

    @Resource
    private RagChatService ragChatService;

    /**
     * 流式问答端点（AI SDK 协议 SSE）
     *
     * @param request  useChat 请求体（完整消息历史，取最后一条用户消息）
     * @param response 用于写入协议版本响应头（须在流提交前设置）
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody RagChatStreamRequest request, HttpServletResponse response) {
        response.setHeader(UI_MESSAGE_STREAM_HEADER, UI_MESSAGE_STREAM_VERSION);

        // 无超时：生成时长不可预估，断连由 onCompletion/onTimeout 兜底
        SseEmitter emitter = new SseEmitter(0L);

        String userText = request == null ? null : request.lastUserText();
        if (userText == null) {
            // 空输入：按 AI SDK 协议错误形态回包（error part + finish part + [DONE]），前端 useChat 可感知
            sendPayload(emitter, "{\"type\":\"error\",\"errorText\":\"消息内容不能为空\"}");
            sendPayload(emitter, "{\"type\":\"finish\",\"finishReason\":\"error\"}");
            sendPayload(emitter, "[DONE]");
            emitter.complete();
            return emitter;
        }

        // 检索与来源解析（同步完成，发生在模型流开始之前）
        RagChatService.RagChatStream stream;
        try {
            stream = ragChatService.startStream(userText);
        } catch (Exception e) {
            // 检索失败（Milvus/嵌入服务不可用等）：以协议错误帧回包，前端错误条可感知
            log.error("[RAG Chat] 检索失败: question={}", abbreviate(userText, 80), e);
            sendPayload(emitter, "{\"type\":\"error\",\"errorText\":"
                    + quoteJson(e.getMessage() == null ? "检索失败，请稍后重试" : e.getMessage()) + "}");
            sendPayload(emitter, "{\"type\":\"finish\",\"finishReason\":\"error\"}");
            sendPayload(emitter, "[DONE]");
            emitter.complete();
            return emitter;
        }

        // 来源随 metadata part（start 之后立即下发）一次送达：前端 message.metadata.sources
        Map<String, Object> metadata = Collections.singletonMap("sources", stream.getSources());
        Flux<SseEvent> sseEvents = aiSdkWrapper.toAiSdkStream(stream.getEvents(), metadata);

        // 自主订阅：request(MAX)，事件到达即写即刷，无背压饥饿
        Disposable disposable = sseEvents
                // 模型流按订阅时调度：移出 Tomcat 工作线程
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> sendPayload(emitter, toPayload(event)),
                        error -> {
                            log.error("[RAG Chat] 流式问答异常: question={}", abbreviate(userText, 80), error);
                            emitter.completeWithError(error);
                        },
                        emitter::complete);

        // 生命周期联动：客户端断开 / 超时 → 取消上游（停止模型请求，避免后台继续耗费 token）
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);

        return emitter;
    }

    /**
     * 写出一帧 SSE 事件（SseEmitter 按 "data:&lt;payload&gt;\n\n" 组帧并 flush）。
     * 写失败（如客户端断开）抛出触发 onError → completeWithError → dispose → 取消上游。
     */
    private void sendPayload(SseEmitter emitter, String payload) {
        try {
            emitter.send(payload, TEXT_PLAIN_UTF8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (IllegalStateException ex) {
            // emitter 已 complete（如上游完成与断开竞争），静默忽略后续帧
            log.debug("[RAG Chat] emitter 已关闭，丢弃事件: {}", ex.getMessage());
        }
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

    /**
     * 动态错误文案转 JSON 字符串字面量（\ 与 " 转义 + 控制字符剔除）
     */
    private static String quoteJson(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", " ");
        return "\"" + escaped + "\"";
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
