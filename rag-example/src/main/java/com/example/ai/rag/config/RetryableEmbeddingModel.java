package com.example.ai.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;

import java.io.IOException;
import java.util.List;

/**
 * 带指数退避重试的嵌入模型装饰器：向量接口偶发超时、瞬断、限流（429）时自动重试。
 * <p>
 * 覆盖 {@link EmbeddingModel} 的两个调用入口（均被本项目的使用方触达）：
 * <ul>
 *   <li>{@link #embed(List)}：{@code MilvusChildRepository.save} 按批向量化（入库路径）</li>
 *   <li>{@link #embed(String)}：{@code dimensions()} 与检索时的查询向量（搜索/问答路径）</li>
 * </ul>
 * 两类瞬态错误都会触发重试：网络层 {@link IOException}（含超时）与 API 层
 * {@code EmbeddingException}（extends SolonException，如 429 限流、5xx）。
 * <p>
 * 无共享可变状态（尝试次数/退避均为方法局部变量），线程安全：并行入库时多个文档
 * 同时重试互不影响。参数来自 {@code ai.rag.embedding.retry-*}（见 RagProperties.Embedding）。
 */
@Slf4j
public class RetryableEmbeddingModel extends EmbeddingModel {

    /** 最大尝试次数（含首次，1 = 不重试） */
    private final int maxAttempts;
    /** 首次重试退避毫秒数，之后按 2^n 指数递增 */
    private final long backoffMillis;

    public RetryableEmbeddingModel(EmbeddingConfig config, int maxAttempts, long backoffMillis) {
        super(config);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    @Override
    public float[] embed(String text) throws IOException {
        return executeWithRetry("embedText(len=" + text.length() + ")", () -> super.embed(text));
    }

    @Override
    public void embed(List<Document> documents) throws IOException {
        executeWithRetry("embedBatch(size=" + documents.size() + ")", () -> {
            super.embed(documents);
            return null;
        });
    }

    /**
     * 指数退避重试：attempt 1..maxAttempts，失败后等待 backoffMillis * 2^(attempt-1)
     * <p>
     * 中断处理：等待中被中断时恢复中断标记并立刻抛出最近一次失败，停止重试。
     */
    private <T> T executeWithRetry(String action, EmbeddingCall<T> call) throws IOException {
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.execute();
            } catch (IOException | RuntimeException e) {
                last = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                // 指数退避，位移封顶 2^10 防溢出
                long backoff = backoffMillis << Math.min(attempt - 1, 10);
                log.warn("[RAG Embedding Retry] {} failed (attempt {}/{}), retry after {}ms: {}",
                        action, attempt, maxAttempts, backoff, e.toString());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw asFailure(action, last);
    }

    /**
     * 还原失败类型：IOException 原样抛出、RuntimeException 原样抛出（不包装），
     * 与不加重试时的异常语义保持一致
     */
    private static IOException asFailure(String action, Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        return new IOException(action + " failed", cause);
    }

    @FunctionalInterface
    private interface EmbeddingCall<T> {
        T execute() throws IOException;
    }
}
