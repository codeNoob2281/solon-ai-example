package com.example.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.interceptor.StreamChain;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Solon AI ChatModel 请求/响应日志拦截器
 * <p>
 * 在 ChatInterceptor 拦截链中打印模型请求体与响应内容：
 * <ul>
 *   <li>call 模式：打印请求 JSON + 响应内容 + usage</li>
 *   <li>stream 模式（4.1 ChatEvent 模型）：打印请求 JSON + 每个事件 + 正文聚合 + usage</li>
 * </ul>
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Slf4j
public class LoggingChatInterceptor implements ChatInterceptor {

    @Override
    public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        long start = System.currentTimeMillis();

        // —— 请求日志 ——
        log.info("[AI Request] model={} stream=false", req.getConfig().getNameOrModel());
        logRequestJson(req);

        ChatResponse resp = chain.doIntercept(req);

        // —— 响应日志 ——
        long elapsed = System.currentTimeMillis() - start;
        log.info("[AI Response] model={} elapsed={}ms", req.getConfig().getNameOrModel(), elapsed);
        if (resp != null) {
            log.info("  content={}", resp.getContent());
            logUsage(resp.getUsage());
        }

        return resp;
    }

    /**
     * 4.1 起流式拦截链类型为 {@code Flux<ChatEvent>}：每个事件是当帧语义快照，
     * 完整聚合结果只从 {@code RESPONSE_END.getResponse()} 读取（不可变 ChatResponse）。
     */
    @Override
    public reactor.core.publisher.Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
        long start = System.currentTimeMillis();
        AtomicInteger eventIndex = new AtomicInteger(0);
        AtomicInteger textDeltaCount = new AtomicInteger(0);
        AtomicReference<String> aggregated = new AtomicReference<>("");
        AtomicReference<AiUsage> usageRef = new AtomicReference<>();

        log.info("[AI Request] model={} stream=true", req.getConfig().getNameOrModel());
        logRequestJson(req);

        return chain.doIntercept(req)
                .doOnNext(event -> {
                    int idx = eventIndex.getAndIncrement();

                    // 正文增量：TEXT_DELTA.getText() 为当帧分片（非累计）
                    if (event.is(ChatEventType.TEXT_DELTA) && event.hasText()) {
                        textDeltaCount.incrementAndGet();
                        aggregated.updateAndGet(prev -> prev + event.getText());
                    }

                    // 终态：RESPONSE_END 携带全流终态聚合（含累计 usage）；
                    // STEP_END / USAGE 事件各自携带用量，兜底记录最后一次
                    if (event.is(ChatEventType.RESPONSE_END) && event.getResponse() != null) {
                        aggregated.set(event.getResponse().getContent());
                        if (event.getResponse().getUsage() != null) {
                            usageRef.set(event.getResponse().getUsage());
                        }
                    } else if (event.getUsage() != null) {
                        usageRef.set(event.getUsage());
                    }

                    log.info("[AI Stream] event={} type={} model={} text={}",
                            idx,
                            event.getType(),
                            req.getConfig().getNameOrModel(),
                            event.hasText() ? event.getText() : "");
                })
                .doOnError(err -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.error("[AI Stream Error] model={} elapsed={}ms error={}",
                            req.getConfig().getNameOrModel(),
                            elapsed,
                            err.getMessage(), err);
                })
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[AI Stream Done] model={} elapsed={}ms events={} textDeltas={} aggregatedLength={}",
                            req.getConfig().getNameOrModel(),
                            elapsed,
                            eventIndex.get(),
                            textDeltaCount.get(),
                            aggregated.get().length());
                    logUsage(usageRef.get());
                });
    }

    /**
     * 打印请求体 JSON（API 密钥自动脱敏）
     */
    private void logRequestJson(ChatRequest req) {
        try {
            String json = req.toRequestData();
            String safe = json.replaceAll("(\"api_key\"\\s*:\\s*\")([^\"]+)(\")", "$1***$3")
                    .replaceAll("(\"authorization\"\\s*:\\s*\")([^\"]+)(\")", "$1***$3");
            log.debug("  request={}", safe);
        } catch (Exception e) {
            log.warn("  request=<serialize failed: {}>", e.getMessage());
        }
    }

    /**
     * 打印 AiUsage，null 时跳过。
     */
    private void logUsage(AiUsage usage) {
        if (usage == null) {
            return;
        }

        log.info("[AI Usage] prompt={} completion={} total={}",
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens());

        if (usage.thinkTokens() > 0) {
            log.info("  think={}", usage.thinkTokens());
        }
        if (usage.cacheReadInputTokens() > 0 || usage.cacheCreationInputTokens() > 0) {
            log.info("  cache: read={} create={} rate={}%",
                    usage.cacheReadInputTokens(),
                    usage.cacheCreationInputTokens(),
                    usage.getCacheRate());
        }
    }
}