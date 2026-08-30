package com.example.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
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
 *   <li>stream 模式：打印请求 JSON + 每个 chunk + 聚合结果 + usage</li>
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

    @Override
    public reactor.core.publisher.Flux<ChatResponse> interceptStream(ChatRequest req, StreamChain chain) {
        long start = System.currentTimeMillis();
        AtomicInteger chunkIndex = new AtomicInteger(0);
        AtomicReference<String> aggregated = new AtomicReference<>("");
        AtomicReference<AiUsage> usageRef = new AtomicReference<>();

        log.info("[AI Request] model={} stream=true", req.getConfig().getNameOrModel());
        logRequestJson(req);

        return chain.doIntercept(req)
                .doOnNext(resp -> {
                    int idx = chunkIndex.getAndIncrement();
                    String content = resp.getContent();
                    if (content != null) {
                        aggregated.updateAndGet(prev -> prev + content);
                    }
                    if (resp.isFinished() && resp.getUsage() != null) {
                        usageRef.set(resp.getUsage());
                    }
                    log.info("[AI Stream] chunk={}/{} model={} content={}",
                            idx,
                            resp.hasChoices() ? resp.getChoices().size() : 0,
                            req.getConfig().getNameOrModel(),
                            content);
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
                    log.info("[AI Stream Done] model={} elapsed={}ms chunks={} aggregatedLength={}",
                            req.getConfig().getNameOrModel(),
                            elapsed,
                            chunkIndex.get(),
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