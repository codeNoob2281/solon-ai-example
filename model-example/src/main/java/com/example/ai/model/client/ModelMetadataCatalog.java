package com.example.ai.model.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.ai.model.dto.RemoteModelInfo;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型元信息目录（models.dev 在线目录，内存缓存）
 * <p>
 * 用途：供应商 {@code /v1/models} 通常只返回模型 id，不返回窗口信息；
 * 「不填上下文窗口 / 最大输出 tokens 时自动获取」由本目录兜底补全：
 * <ol>
 *   <li>供应商返回字段优先（{@link ProviderApiClient} 已先填）；</li>
 *   <li>缺失字段按「供应商类型 → models.dev provider」优先查，再按模型 id 全目录搜索；</li>
 *   <li>仍未命中则保持 null（前端留空，用户可手填）。</li>
 * </ol>
 * <p>
 * 缓存策略：首次使用时拉取 {@code https://models.dev/api.json}（约 6MB），成功后缓存
 * {@link #CACHE_TTL_MILLIS}；失败不抛出（目录只是辅助），进入冷却窗口避免配置页每次保存都卡住。
 * 目录地址可用 {@code ai.model.metadata-catalog-url} 覆盖，置空则完全禁用远程补全。
 */
@Slf4j
@Component
public class ModelMetadataCatalog {

    /** 目录地址（可用 ai.model.metadata-catalog-url 覆盖；空串 = 禁用） */
    @org.springframework.beans.factory.annotation.Value("${ai.model.metadata-catalog-url:https://models.dev/api.json}")
    private String catalogUrl;

    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MILLIS = 12 * 60 * 60 * 1000L;

    /** 拉取失败后的冷却时间（毫秒），避免无网环境下每次请求都阻塞 */
    private static final long FAILURE_COOLDOWN_MILLIS = 5 * 60 * 1000L;

    /** 拉取超时秒数 */
    private static final int TIMEOUT_SECONDS = 30;

    /** 供应商类型 → models.dev provider id（用于提升命中优先级） */
    private static final Map<String, String> TYPE_TO_CATALOG_PROVIDER;

    static {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("openai", "openai");
        mapping.put("deepseek", "deepseek");
        mapping.put("qwen", "alibaba-cn");
        mapping.put("moonshot", "moonshotai-cn");
        mapping.put("zhipu", "zhipuai");
        mapping.put("ollama", "ollama-cloud");
        TYPE_TO_CATALOG_PROVIDER = Collections.unmodifiableMap(mapping);
    }

    /** 模型条目缓存：modelId -> 元信息 */
    private volatile Map<String, CatalogModel> entries = Collections.emptyMap();

    /** 各 provider 下的模型 id 集合（优先命中用） */
    private volatile Map<String, Map<String, CatalogModel>> providerEntries = Collections.emptyMap();

    private volatile long loadedAt = 0L;

    private volatile long failedAt = 0L;

    /**
     * 按需补全缺失字段（供应商返回优先，目录兜底）
     *
     * @param providerType 供应商类型（如 openai / deepseek；可空）
     * @param model        待补全的模型信息（原地修改）
     */
    public void fillMissing(String providerType, RemoteModelInfo model) {
        if (model == null || model.getId() == null) {
            return;
        }
        boolean needWindow = model.getContextWindow() == null;
        boolean needOutput = model.getMaxOutputTokens() == null;
        boolean needInputTypes = model.getInputTypes() == null || model.getInputTypes().isEmpty();
        if (!needWindow && !needOutput && !needInputTypes) {
            return;
        }
        CatalogModel found = lookup(providerType, model.getId());
        if (found == null) {
            return;
        }
        if (needWindow && found.contextWindow != null) {
            model.setContextWindow(found.contextWindow);
        }
        if (needOutput && found.maxOutputTokens != null) {
            model.setMaxOutputTokens(found.maxOutputTokens);
        }
        if (needInputTypes && !found.inputTypes.isEmpty()) {
            model.setInputTypes(new ArrayList<>(found.inputTypes));
        }
    }

    /**
     * 单模型元信息查询（供前端「一键获取」后单独刷新某行使用）
     *
     * @return 命中返回元信息，未命中返回 null（不访问网络失败时同样返回 null）
     */
    public RemoteModelInfo lookupOne(String providerType, String modelId) {
        CatalogModel found = lookup(providerType, modelId);
        if (found == null) {
            return null;
        }
        RemoteModelInfo info = new RemoteModelInfo();
        info.setId(modelId);
        info.setContextWindow(found.contextWindow);
        info.setMaxOutputTokens(found.maxOutputTokens);
        info.setInputTypes(new ArrayList<>(found.inputTypes));
        return info;
    }

    // ------------------------------------------------------------
    // 内部实现

    private CatalogModel lookup(String providerType, String modelId) {
        Map<String, CatalogModel> snapshot = ensureLoaded();
        if (snapshot.isEmpty()) {
            return null;
        }
        // 1. 按供应商类型优先
        String catalogProvider = providerType == null ? null
                : TYPE_TO_CATALOG_PROVIDER.get(providerType.toLowerCase(Locale.ROOT));
        if (catalogProvider != null) {
            Map<String, CatalogModel> scoped = providerEntries.get(catalogProvider);
            if (scoped != null) {
                CatalogModel hit = resolveIn(scoped, modelId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // 2. 全目录搜索（含各家转发/别名）
        return resolveIn(snapshot, modelId);
    }

    /** 先精确、再忽略 provider 前缀、最后唯一后缀匹配 */
    private CatalogModel resolveIn(Map<String, CatalogModel> source, String modelId) {
        String id = modelId.trim();
        CatalogModel exact = source.get(id);
        if (exact != null) {
            return exact;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        CatalogModel lowerHit = source.get(lower);
        if (lowerHit != null) {
            return lowerHit;
        }
        int slash = lower.lastIndexOf('/');
        if (slash >= 0 && slash < lower.length() - 1) {
            CatalogModel stripped = source.get(lower.substring(slash + 1));
            if (stripped != null) {
                return stripped;
            }
        }
        CatalogModel suffixHit = null;
        for (Map.Entry<String, CatalogModel> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("/" + lower) || key.endsWith(":" + lower)) {
                if (suffixHit != null) {
                    return null; // 多个候选，不做猜测
                }
                suffixHit = entry.getValue();
            }
        }
        return suffixHit;
    }

    private Map<String, CatalogModel> ensureLoaded() {
        long now = System.currentTimeMillis();
        if (!entries.isEmpty() && now - loadedAt < CACHE_TTL_MILLIS) {
            return entries;
        }
        if (failedAt > 0 && now - failedAt < FAILURE_COOLDOWN_MILLIS) {
            return entries;
        }
        if (catalogUrl == null || catalogUrl.trim().isEmpty()) {
            return entries;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (!entries.isEmpty() && now - loadedAt < CACHE_TTL_MILLIS) {
                return entries;
            }
            if (failedAt > 0 && now - failedAt < FAILURE_COOLDOWN_MILLIS) {
                return entries;
            }
            try {
                load();
                loadedAt = System.currentTimeMillis();
                failedAt = 0L;
                log.info("[Model Config] models.dev 目录已加载: models={}, providers={}",
                        entries.size(), providerEntries.size());
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                log.warn("[Model Config] models.dev 目录加载失败（窗口信息需手工填写）: {}", e.getMessage());
            }
            return entries;
        }
    }

    private void load() {
        HttpUtils http = HttpUtils.http(catalogUrl.trim())
                .timeout(TIMEOUT_SECONDS)
                .header("Accept", "application/json");
        String body;
        try (HttpResponse response = http.exec("GET")) {
            if (response.code() < 200 || response.code() >= 300) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            body = response.bodyAsString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        JSONObject root = JSONObject.parseObject(body);
        Map<String, CatalogModel> flat = new LinkedHashMap<>();
        Map<String, Map<String, CatalogModel>> byProvider = new LinkedHashMap<>();
        for (String providerId : root.keySet()) {
            JSONObject provider = root.getJSONObject(providerId);
            if (provider == null) {
                continue;
            }
            JSONObject models = provider.getJSONObject("models");
            if (models == null) {
                continue;
            }
            Map<String, CatalogModel> scoped = new LinkedHashMap<>();
            for (String modelId : models.keySet()) {
                CatalogModel meta = toCatalogModel(modelId, models.getJSONObject(modelId));
                if (meta == null) {
                    continue;
                }
                scoped.put(modelId.toLowerCase(Locale.ROOT), meta);
                // 全目录表：先到先得，保持稳定（同 id 多 provider 时以目录顺序第一条为准）
                flat.putIfAbsent(modelId.toLowerCase(Locale.ROOT), meta);
            }
            byProvider.put(providerId, scoped);
        }
        this.providerEntries = byProvider;
        this.entries = flat;
    }

    private CatalogModel toCatalogModel(String modelId, JSONObject node) {
        if (node == null) {
            return null;
        }
        CatalogModel meta = new CatalogModel();
        meta.id = modelId;
        JSONObject limit = node.getJSONObject("limit");
        if (limit != null) {
            meta.contextWindow = positiveOrNull(limit.getInteger("context"));
            meta.maxOutputTokens = positiveOrNull(limit.getInteger("output"));
        }
        JSONObject modalities = node.getJSONObject("modalities");
        JSONArray input = modalities == null ? null : modalities.getJSONArray("input");
        if (input != null) {
            List<String> types = new ArrayList<>();
            for (int i = 0; i < input.size(); i++) {
                String value = input.getString(i);
                if ("text".equalsIgnoreCase(value)) {
                    types.add("text");
                } else if ("image".equalsIgnoreCase(value)) {
                    types.add("image");
                }
            }
            if (!types.isEmpty()) {
                meta.inputTypes = types;
            }
        }
        return meta;
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    /** 目录中的单模型元信息 */
    private static final class CatalogModel {
        private String id;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> inputTypes = Collections.emptyList();
    }
}
