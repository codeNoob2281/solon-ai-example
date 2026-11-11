package com.example.ai.rag.rerank;

import com.example.ai.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序策略选择器：按配置 {@code ai.rag.rerank.*}（{@link RagProperties.Rerank}）
 * 从容器内全部 {@link RagRerankStrategy} Bean 中选出当前使用的策略。
 * <p>
 * 选择规则（启动时调用一次）：
 * <ul>
 *   <li>{@code enabled=false} → 截断策略（{@code truncate}）；</li>
 *   <li>配置的策略标识为 {@code truncate} → 截断策略；</li>
 *   <li>{@code api-url} 或 {@code model} 未配置 → 截断策略（视为未启用重排服务，
 *       INFO 提示，不阻断启动）；</li>
 *   <li>配置了未知标识 → 抛出异常使启动失败（静态配置错误应尽早暴露）；</li>
 *   <li>策略标识重复 → 抛出异常使启动失败（避免选择歧义）。</li>
 * </ul>
 */
@Slf4j
@Component
public class RagRerankStrategyResolver {

    private final RagProperties properties;
    private final Map<String, RagRerankStrategy> strategiesByName;
    private final RagRerankStrategy truncateStrategy;

    public RagRerankStrategyResolver(RagProperties properties, List<RagRerankStrategy> strategies) {
        this.properties = properties;

        Map<String, RagRerankStrategy> byName = new LinkedHashMap<>();
        for (RagRerankStrategy strategy : strategies) {
            RagRerankStrategy previous = byName.put(strategy.name(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate RagRerankStrategy name '" + strategy.name()
                        + "': " + previous.getClass().getName() + " / " + strategy.getClass().getName());
            }
        }
        this.strategiesByName = Collections.unmodifiableMap(byName);

        RagRerankStrategy truncate = byName.get(RagRerankStrategy.NAME_TRUNCATE);
        if (truncate == null) {
            throw new IllegalStateException("RagRerankStrategy not found: "
                    + RagRerankStrategy.NAME_TRUNCATE);
        }
        this.truncateStrategy = truncate;
        log.info("[RAG Rerank] available strategies={}", byName.keySet());
    }

    /**
     * 解析当前应使用的重排序策略（启动时调用一次）
     */
    public RagRerankStrategy resolve() {
        RagProperties.Rerank cfg = properties.getRerank();

        if (!cfg.isEnabled()) {
            log.info("[RAG Rerank] disabled by ai.rag.rerank.enabled=false, use truncation");
            return truncateStrategy;
        }

        String configured = cfg.getStrategy();
        if (StringUtils.hasText(configured)
                && RagRerankStrategy.NAME_TRUNCATE.equals(configured.trim())) {
            return truncateStrategy;
        }

        if (!StringUtils.hasText(cfg.getApiUrl()) || !StringUtils.hasText(cfg.getModel())) {
            log.info("[RAG Rerank] api-url/model not configured, fallback to truncation");
            return truncateStrategy;
        }

        String name = StringUtils.hasText(configured)
                ? configured.trim() : RagRerankStrategy.NAME_MODEL;
        RagRerankStrategy strategy = strategiesByName.get(name);
        if (strategy == null) {
            throw new IllegalStateException("Unknown ai.rag.rerank.strategy '" + name
                    + "', available: " + strategiesByName.keySet());
        }
        log.info("[RAG Rerank] selected strategy={}", strategy.name());
        return strategy;
    }
}
