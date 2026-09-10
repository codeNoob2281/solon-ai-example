package com.example.ai.rag.retrieval;

import com.example.ai.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索策略选择器：按配置 {@code ai.rag.search.strategy}（{@link RagProperties.Search}）
 * 从容器内全部 {@link RagRetrievalStrategy} Bean 中选出当前使用的策略。
 * <p>
 * 启动时构建注册表（策略标识 → 实现）：
 * <ul>
 *   <li>配置为空/空白 → 使用默认策略 {@link RagRetrievalStrategy#DEFAULT_STRATEGY}；</li>
 *   <li>配置了未知标识 → 抛出异常使启动失败（静态配置错误应尽早暴露，避免静默回落）；</li>
 *   <li>策略标识重复 → 抛出异常使启动失败（避免选择歧义）。</li>
 * </ul>
 */
@Slf4j
@Component
public class RagRetrievalStrategyResolver {

    private final RagProperties properties;
    private final Map<String, RagRetrievalStrategy> strategiesByName;
    private final RagRetrievalStrategy defaultStrategy;

    public RagRetrievalStrategyResolver(RagProperties properties,
                                        List<RagRetrievalStrategy> strategies) {
        this.properties = properties;

        Map<String, RagRetrievalStrategy> byName = new LinkedHashMap<>();
        for (RagRetrievalStrategy strategy : strategies) {
            RagRetrievalStrategy previous = byName.put(strategy.name(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate RagRetrievalStrategy name '" + strategy.name()
                        + "': " + previous.getClass().getName() + " / " + strategy.getClass().getName());
            }
        }
        this.strategiesByName = Collections.unmodifiableMap(byName);

        RagRetrievalStrategy def = byName.get(RagRetrievalStrategy.DEFAULT_STRATEGY);
        if (def == null) {
            throw new IllegalStateException("Default RagRetrievalStrategy not found: "
                    + RagRetrievalStrategy.DEFAULT_STRATEGY);
        }
        this.defaultStrategy = def;
        log.info("[RAG Retrieval] available strategies={} default={}", byName.keySet(), def.name());
    }

    /**
     * 解析当前应使用的检索策略（启动时调用一次）
     */
    public RagRetrievalStrategy resolve() {
        String configured = properties.getSearch().getStrategy();
        if (configured == null || configured.trim().isEmpty()) {
            return defaultStrategy;
        }
        RagRetrievalStrategy strategy = strategiesByName.get(configured.trim());
        if (strategy == null) {
            throw new IllegalStateException("Unknown ai.rag.search.strategy '" + configured
                    + "', available: " + strategiesByName.keySet());
        }
        return strategy;
    }
}
