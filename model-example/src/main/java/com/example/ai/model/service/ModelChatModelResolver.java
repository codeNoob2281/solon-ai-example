package com.example.ai.model.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.ai.core.ChatModelResolver;
import com.example.ai.model.entity.McModel;
import com.example.ai.model.entity.McProvider;
import com.example.ai.model.storage.ModelConfigStore;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话模型解析器实现（{@link ChatModelResolver} 的 model-example 实现）
 * <p>
 * 按模型配置（mc_model + 所属 mc_provider）构建 Solon AI {@link ChatModel}：
 * <ul>
 *   <li>apiUrl 取供应商 Base URL（OpenAI 方言在缺版本段时自动补 /v1/chat/completions）；</li>
 *   <li>apiKey 取供应商 API Key；provider/standard 固定 openai（所有供应商均按 OpenAI 兼容协议调用）；</li>
 *   <li>contextLength 取模型上下文窗口（供框架上下文压缩与用量基准）；</li>
 *   <li>实例按「供应商配置指纹 + 模型名 + 窗口」缓存，配置变更时由
 *       {@link ModelConfigService} 调 {@link #evictAll()} 失效。</li>
 * </ul>
 * 推理档位：命中档位的 {@code apiValue} 原样返回给调用方，由调用方决定下发方式
 * （agent-example 走 {@code reasoning_effort(...)}，空值表示不下发）。
 */
@Slf4j
@Service
public class ModelChatModelResolver implements ChatModelResolver {

    /** 可用性测试的请求超时秒数（一次最小对话，不该等框架默认 120s） */
    private static final int TEST_TIMEOUT_SECONDS = 30;

    @Resource
    private ModelConfigStore store;

    /** ChatModel 缓存：缓存 key → 实例 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    @Override
    public ResolvedChatModel resolve(String modelId, String reasoningLevelKey) {
        Long id = parseId(modelId);
        if (id == null) {
            return null;
        }
        McModel model = store.findModelById(id);
        if (model == null || model.getStatus() == null || model.getStatus() != 1) {
            log.warn("[Model Config] 模型不存在或已禁用: modelId={}", modelId);
            return null;
        }
        McProvider provider = store.findProviderById(model.getProviderId());
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            log.warn("[Model Config] 供应商不存在或已禁用: providerId={}, modelId={}",
                    model.getProviderId(), modelId);
            return null;
        }

        String cacheKey = cacheKey(provider, model);
        ChatModel chatModel = cache.computeIfAbsent(cacheKey, key -> build(provider, model));
        return new ResolvedChatModel(chatModel, reasoningLevelKey, resolveReasoningApiValue(model, reasoningLevelKey));
    }

    /**
     * 清空模型实例缓存（供应商 / 模型配置变更后调用）
     */
    public void evictAll() {
        cache.clear();
    }

    /**
     * 构建用于「可用性测试」的模型实例（不校验启用状态、不走缓存）
     * <p>
     * 与 {@link #resolve} 的区别：
     * <ul>
     *   <li>禁用中的模型/供应商同样可测（配置页常见「先测通再启用」）；</li>
     *   <li>不复用缓存实例，避免测试参数（如较小超时）污染对话路径；</li>
     *   <li>超时压到 {@link #TEST_TIMEOUT_SECONDS} 秒：测试是一次最小对话，不该等两分钟。</li>
     * </ul>
     *
     * @throws IllegalStateException 模型或供应商不存在时
     */
    public ChatModel buildForTest(Long modelId) {
        McModel model = store.findModelById(modelId);
        if (model == null) {
            throw new IllegalStateException("模型不存在：" + modelId);
        }
        McProvider provider = store.findProviderById(model.getProviderId());
        if (provider == null) {
            throw new IllegalStateException("模型所属供应商不存在：" + model.getProviderId());
        }
        return build(provider, model, TEST_TIMEOUT_SECONDS);
    }

    // ------------------------------------------------------------
    // 内部实现

    private ChatModel build(McProvider provider, McModel model) {
        return build(provider, model, 0);
    }

    /**
     * 构建 ChatModel
     *
     * @param timeoutSeconds 超时秒数；&lt;=0 表示沿用框架默认（120s）
     */
    private ChatModel build(McProvider provider, McModel model, int timeoutSeconds) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(provider.getBaseUrl());
        config.setApiKey(provider.getApiKey());
        config.setProvider("openai");
        config.setStandard("openai");
        config.setModel(model.getModelName());
        int contextWindow = model.getContextWindow() == null ? 0 : model.getContextWindow();
        // 0 表示未知：不覆盖框架默认（用量面板按未知处理）
        if (contextWindow > 0) {
            config.setContextLength(contextWindow);
        }
        if (timeoutSeconds > 0) {
            config.setTimeout(Duration.ofSeconds(timeoutSeconds));
        }
        log.info("[Model Config] 构建 ChatModel: provider={}, model={}, apiUrl={}",
                provider.getName(), model.getModelName(), provider.getBaseUrl());
        return ChatModel.of(config).build();
    }

    /**
     * 缓存 key：供应商连接信息 + 模型名 + 窗口（配置变更即自然换 key，配合 evictAll 双保险）
     */
    private String cacheKey(McProvider provider, McModel model) {
        return provider.getId() + "|" + provider.getBaseUrl() + "|" + apiKeyFingerprint(provider.getApiKey())
                + "|" + model.getModelName() + "|" + model.getContextWindow();
    }

    private String apiKeyFingerprint(String apiKey) {
        return apiKey == null ? "0" : Integer.toHexString(apiKey.hashCode());
    }

    /**
     * 推理档位 → API 参数值：命中档位返回其 apiValue（空串表示不下发）；未配置档位时按默认映射
     */
    private String resolveReasoningApiValue(McModel model, String reasoningLevelKey) {
        if (reasoningLevelKey == null || reasoningLevelKey.trim().isEmpty()) {
            return null;
        }
        String key = reasoningLevelKey.trim();
        JSONArray levels = parseLevels(model.getReasoningLevels());
        for (int i = 0; i < levels.size(); i++) {
            JSONObject node = levels.getJSONObject(i);
            if (node == null) {
                continue;
            }
            if (key.equalsIgnoreCase(node.getString("key"))) {
                String apiValue = node.getString("apiValue");
                return apiValue == null ? "" : apiValue.trim();
            }
        }
        // 未配置档位：off 不下发；low/medium/high 直接按同名下发
        if ("off".equalsIgnoreCase(key)) {
            return "";
        }
        return key;
    }

    private JSONArray parseLevels(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONArray();
        }
        try {
            return JSON.parseArray(json);
        } catch (RuntimeException e) {
            log.warn("[Model Config] reasoningLevels 解析失败: {}", e.getMessage());
            return new JSONArray();
        }
    }

    private Long parseId(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(modelId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
