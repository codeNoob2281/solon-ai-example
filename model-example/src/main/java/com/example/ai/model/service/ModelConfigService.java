package com.example.ai.model.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.ai.model.client.ModelMetadataCatalog;
import com.example.ai.model.client.ProviderApiClient;
import com.example.ai.model.dto.ModelRequest;
import com.example.ai.model.dto.ModelResponse;
import com.example.ai.model.dto.ModelTestResult;
import com.example.ai.model.dto.ProviderRequest;
import com.example.ai.model.dto.ProviderResponse;
import com.example.ai.model.dto.ProviderTestResult;
import com.example.ai.model.dto.ReasoningLevel;
import com.example.ai.model.dto.RemoteModelInfo;
import com.example.ai.model.dto.RemoteModelQueryRequest;
import com.example.ai.model.entity.McModel;
import com.example.ai.model.entity.McProvider;
import com.example.ai.model.storage.ModelConfigStore;
import com.example.ai.model.support.ProviderTypes;
import com.example.ai.model.support.TokenSize;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商 / 模型配置管理服务（模型配置页后端）
 * <p>
 * 约定：
 * <ul>
 *   <li>供应商与模型一次性保存（编辑页整体提交，{@code models == null} 表示不改模型，{@code []} 表示清空）；</li>
 *   <li>API Key 明文入库，回显只给「是否已配置 + 末四位」；编辑时留空表示不修改；</li>
 *   <li>上下文窗口 / 最大输出 tokens 支持 {@code 1M} / {@code 200K} 简写；
 *       未填时先取供应商 {@code /models} 返回字段，再回落 models.dev 目录；</li>
 *   <li>推理档位存 JSON 数组，未配置时按默认 off/low/medium/high 展示（不落库）。</li>
 * </ul>
 * 业务异常统一用 {@link IllegalArgumentException}（参数问题）/ {@link IllegalStateException}（冲突 / 状态问题），
 * 由控制器局部映射为 400 / 409。
 */
@Slf4j
@Service
public class ModelConfigService {

    /** 默认推理档位 key 列表（用于校验 off 语义） */
    private static final String LEVEL_KEY_OFF = "off";

    /** 支持的输入类型 */
    private static final List<String> ALLOWED_INPUT_TYPES = Arrays.asList("text", "image");

    /** 模型可用性测试提示词（固定短语：最短往返、结果易判定） */
    private static final String TEST_PROMPT = "请回复pong";

    /**
     * 可用性测试的输出上限。
     * <p>取值比「只回 pong」所需大得多：推理模型可能忽略关闭思考的指令，
     * 把额度先花在思维链上——额度太小会导致正文为空（finish_reason=length），
     * 从而把「可用但爱思考」的模型误判为不可用。
     */
    private static final long TEST_MAX_TOKENS = 512L;

    /** 测试结果里回显的回复长度上限 */
    private static final int TEST_REPLY_MAX = 120;

    @Resource
    private ModelConfigStore store;

    @Resource
    private ProviderApiClient providerApiClient;

    @Resource
    private ModelMetadataCatalog metadataCatalog;

    @Resource
    private ModelChatModelResolver chatModelResolver;

    // ------------------------------------------------------------
    // 查询

    /**
     * 供应商列表（含各自模型列表，按创建时间倒序）
     */
    public List<ProviderResponse> listProviders() {
        List<McProvider> providers = store.listProviders();
        List<ProviderResponse> result = new ArrayList<>(providers.size());
        for (McProvider provider : providers) {
            result.add(toProviderResponse(provider, store.listModelsByProvider(provider.getId())));
        }
        return result;
    }

    /**
     * 供应商详情（含模型列表）
     */
    public ProviderResponse getProvider(Long id) {
        McProvider provider = requireProvider(id);
        return toProviderResponse(provider, store.listModelsByProvider(id));
    }

    /**
     * 供应商类型预设（前端下拉 + 默认 Base URL 提示）
     */
    public List<Map<String, String>> listTypes() {
        List<Map<String, String>> types = new ArrayList<>();
        for (String type : ProviderTypes.KNOWN_TYPES) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", type);
            item.put("label", ProviderTypes.label(type));
            item.put("defaultBaseUrl", ProviderTypes.defaultBaseUrl(type));
            types.add(item);
        }
        return types;
    }

    /**
     * 默认推理档位（新建模型时前端预填）
     */
    public List<ReasoningLevel> defaultReasoningLevels() {
        return ProviderTypes.defaultReasoningLevels();
    }

    // ------------------------------------------------------------
    // 供应商增删改

    /**
     * 新增供应商（可一并保存模型列表）
     */
    public ProviderResponse createProvider(ProviderRequest request) {
        validateProviderRequest(request, null);

        McProvider provider = new McProvider();
        applyProviderFields(provider, request);
        store.insertProvider(provider);

        if (request.getModels() != null) {
            saveModels(provider, request.getModels());
        }
        chatModelResolver.evictAll();
        log.info("[Model Config] provider created: id={}, name={}", provider.getId(), provider.getName());
        return toProviderResponse(provider, store.listModelsByProvider(provider.getId()));
    }

    /**
     * 编辑供应商（models 为 null 表示不改模型；传列表则整体覆盖）
     */
    public ProviderResponse updateProvider(Long id, ProviderRequest request) {
        McProvider provider = requireProvider(id);
        validateProviderRequest(request, id);

        applyProviderFields(provider, request);
        store.updateProvider(provider);

        if (request.getModels() != null) {
            saveModels(provider, request.getModels());
        }
        chatModelResolver.evictAll();
        log.info("[Model Config] provider updated: id={}, name={}", provider.getId(), provider.getName());
        return toProviderResponse(provider, store.listModelsByProvider(id));
    }

    /**
     * 删除供应商（级联删除其全部模型）
     */
    public void deleteProvider(Long id) {
        McProvider provider = requireProvider(id);
        long modelCount = store.countModelsByProvider(id);
        store.deleteProviderCascade(id);
        chatModelResolver.evictAll();
        log.info("[Model Config] provider deleted: id={}, name={}, models={}", id, provider.getName(), modelCount);
    }

    // ------------------------------------------------------------
    // 供应商连通性 / 远程模型

    /**
     * 测试供应商连通性（只测可达性与凭据，不校验具体模型）
     */
    public ProviderTestResult testProvider(Long id) {
        McProvider provider = requireProvider(id);
        return providerApiClient.testConnection(provider.getType(), provider.getBaseUrl(), provider.getApiKey());
    }

    /**
     * 测试单个模型是否可用（配置页「测试模型」入口）
     * <p>
     * 用固定提示词发起一次最小化同步对话，判定口径：
     * <ul>
     *   <li>拿到非空正文 → 可用；</li>
     *   <li>正文为空但返回了思考内容 → 仍算可用（模型通了，只是把额度花在思考上，
     *       额度见 {@link #TEST_MAX_TOKENS}）；</li>
     *   <li>两者都为空 / 抛异常 → 不可用（附 finish_reason 便于定位）。</li>
     * </ul>
     * 单模型失败不影响其它模型（批量测试时逐条汇总，不中断）。
     */
    public ModelTestResult testModel(Long modelId) {
        McModel model = store.findModelById(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在：" + modelId);
        }
        McProvider provider = store.findProviderById(model.getProviderId());
        String displayName = model.getDisplayName() == null || model.getDisplayName().trim().isEmpty()
                ? model.getModelName() : model.getDisplayName();

        long start = System.currentTimeMillis();
        ModelTestResult result = new ModelTestResult();
        result.setModelId(model.getId());
        result.setModelName(model.getModelName());
        result.setDisplayName(displayName);

        try {
            ChatModel chatModel = chatModelResolver.buildForTest(modelId);
            ChatResponse response = chatModel.prompt(TEST_PROMPT)
                    .options(options -> {
                        options.max_tokens(TEST_MAX_TOKENS);
                        options.temperature(0);
                        // 统一开关：框架只对「已知模型族」（qwen/deepseek/glm/kimi/minimax 等）映射出
                        // enable_thinking / thinking.type；未知模型（如 hy4-preview）会静默不写出，
                        // 思考仍会吃掉额度。因此再显式下发两种最常见的关闭字段作为兜底：
                        //   thinking.type=disabled（DeepSeek/Kimi/智谱系）与 enable_thinking=false（Qwen 系）
                        // 二者同发对忽略未知字段的网关无副作用（严格校验 400 的网关少见，且失败会被下方捕获）。
                        options.thinking(false);
                        options.optionSet("enable_thinking", false);
                    })
                    .call();

            // 优先取正文；正文为空但拿到了思考内容时，说明模型确实可用（只是把额度花在思考上）
            String content = trimToNull(response.getContent());
            String thinking = trimToNull(response.getThinking());
            result.setElapsedMs(System.currentTimeMillis() - start);

            if (content != null) {
                result.setSuccess(true);
                result.setReply(abbreviate(content, TEST_REPLY_MAX));
                result.setMessage("可用，回复：" + result.getReply());
            } else if (thinking != null) {
                result.setSuccess(true);
                result.setMessage("可用（模型未返回正文，仅返回思考内容 " + thinking.length()
                        + " 字；finish=" + response.getFinishReason() + "）");
            } else {
                result.setSuccess(false);
                result.setMessage("模型返回空内容（finish=" + response.getFinishReason()
                        + "，若为 length 说明思考占满了 max_tokens=" + TEST_MAX_TOKENS + "）");
            }
        } catch (Exception e) {
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            result.setMessage("调用失败：" + rootMessage(e));
            log.warn("[Model Config] model test failed: provider={}, model={}, cause={}",
                    provider == null ? null : provider.getName(), model.getModelName(), e.getMessage());
        }
        return result;
    }

    /**
     * 批量测试模型（逐条测试，单条失败不影响其余；调用方负责并发/分批）
     */
    public List<ModelTestResult> testModels(List<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个模型");
        }
        List<ModelTestResult> results = new ArrayList<>(modelIds.size());
        for (Long modelId : modelIds) {
            if (modelId == null) {
                continue;
            }
            try {
                results.add(testModel(modelId));
            } catch (IllegalArgumentException e) {
                // 模型已被删除等：记一条失败，不中断整批
                ModelTestResult missing = new ModelTestResult();
                missing.setModelId(modelId);
                missing.setSuccess(false);
                missing.setMessage(e.getMessage());
                results.add(missing);
            }
        }
        return results;
    }

    /**
     * 一键获取供应商远程模型列表（窗口信息按「供应商字段优先 → models.dev 兜底」补全）
     */
    public List<RemoteModelInfo> listRemoteModels(Long id) {
        McProvider provider = requireProvider(id);
        return providerApiClient.listModels(provider.getType(), provider.getBaseUrl(), provider.getApiKey());
    }

    /**
     * 按草稿参数获取远程模型列表（新供应商尚未保存时使用）
     * <p>
     * {@code providerId} 非空且 apiKey 留空时沿用已保存的 Key；
     * 否则按提交的 apiKey（可空——本地 Ollama 等无需鉴权的服务）。
     */
    public List<RemoteModelInfo> listRemoteModelsByDraft(RemoteModelQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String baseUrl = trimToNull(request.getBaseUrl());
        if (baseUrl == null) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        String apiKey = trimToNull(request.getApiKey());
        if (apiKey == null && request.getProviderId() != null) {
            McProvider saved = store.findProviderById(request.getProviderId());
            apiKey = saved == null ? null : saved.getApiKey();
        }
        return providerApiClient.listModels(
                ProviderTypes.normalize(request.getType()), trimTrailingSlash(baseUrl), apiKey);
    }

    /**
     * 补全已保存模型的缺失元信息（上下文窗口 / 最大输出 tokens / 输入类型）
     *
     * @return 补全后的供应商详情（含模型列表）
     */
    public ProviderResponse enrichModels(Long id) {
        McProvider provider = requireProvider(id);
        List<McModel> models = store.listModelsByProvider(id);
        int filled = 0;
        for (McModel model : models) {
            if (model.getContextWindow() != null && model.getContextWindow() > 0
                    && model.getMaxOutputTokens() != null && model.getMaxOutputTokens() > 0) {
                continue;
            }
            RemoteModelInfo probe = new RemoteModelInfo();
            probe.setId(model.getModelName());
            String inputTypes = model.getInputTypes();
            probe.setInputTypes(inputTypes == null || inputTypes.trim().isEmpty()
                    ? null
                    : new ArrayList<>(Arrays.asList(inputTypes.split(","))));
            metadataCatalog.fillMissing(provider.getType(), probe);
            boolean changed = false;
            if ((model.getContextWindow() == null || model.getContextWindow() <= 0)
                    && probe.getContextWindow() != null) {
                model.setContextWindow(probe.getContextWindow());
                changed = true;
            }
            if ((model.getMaxOutputTokens() == null || model.getMaxOutputTokens() <= 0)
                    && probe.getMaxOutputTokens() != null) {
                model.setMaxOutputTokens(probe.getMaxOutputTokens());
                changed = true;
            }
            if (changed) {
                store.updateModel(model);
                filled++;
            }
        }
        chatModelResolver.evictAll();
        log.info("[Model Config] models enriched: providerId={}, filled={}/{}", id, filled, models.size());
        return toProviderResponse(provider, store.listModelsByProvider(id));
    }

    // ------------------------------------------------------------
    // 聊天页选项

    /**
     * 聊天页模型选择器数据：按供应商分组（只含启用且至少有一个启用模型的供应商）
     */
    public List<ModelGroup> listModelGroups() {
        List<McProvider> providers = store.listProviders();
        List<ModelGroup> groups = new ArrayList<>();
        for (McProvider provider : providers) {
            if (provider.getStatus() == null || provider.getStatus() != 1) {
                continue;
            }
            List<McModel> models = store.listModelsByProvider(provider.getId());
            List<ModelOption> options = new ArrayList<>();
            for (McModel model : models) {
                if (model.getStatus() == null || model.getStatus() != 1) {
                    continue;
                }
                options.add(toModelOption(model));
            }
            if (options.isEmpty()) {
                continue;
            }
            ModelGroup group = new ModelGroup();
            group.setProviderId(provider.getId());
            group.setProviderName(provider.getName());
            group.setProviderType(provider.getType());
            group.setModels(options);
            groups.add(group);
        }
        return groups;
    }

    // ------------------------------------------------------------
    // 内部：校验 / 转换 / 保存

    private McProvider requireProvider(Long id) {
        McProvider provider = store.findProviderById(id);
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在：" + id);
        }
        return provider;
    }

    private void validateProviderRequest(ProviderRequest request, Long excludeId) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String name = trimToNull(request.getName());
        if (name == null) {
            throw new IllegalArgumentException("供应商名称不能为空");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException("供应商名称过长（最多 128 字符）");
        }
        if (trimToNull(request.getBaseUrl()) == null) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        McProvider existing = store.findProviderByName(name);
        if (existing != null && !existing.getId().equals(excludeId)) {
            throw new IllegalStateException("供应商名称已存在：" + name);
        }
    }

    private void applyProviderFields(McProvider provider, ProviderRequest request) {
        provider.setName(trimToNull(request.getName()));
        provider.setType(ProviderTypes.normalize(request.getType()));
        provider.setBaseUrl(trimTrailingSlash(request.getBaseUrl()));
        // 编辑时留空表示不修改
        String apiKey = trimToNull(request.getApiKey());
        if (apiKey != null) {
            provider.setApiKey(apiKey);
        }
        provider.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        provider.setRemark(trimToNull(request.getRemark()));
        if (provider.getStatus() != 0 && provider.getStatus() != 1) {
            throw new IllegalArgumentException("供应商状态只能是 1（启用）或 0（禁用）");
        }
    }

    /**
     * 整体覆盖某供应商的模型列表（按提交顺序落库，未携带 id 的视为新增，缺失的已存在的模型被删除）
     */
    private void saveModels(McProvider provider, List<ModelRequest> requests) {
        List<McModel> existing = store.listModelsByProvider(provider.getId());
        Map<Long, McModel> existingById = new LinkedHashMap<>();
        for (McModel model : existing) {
            existingById.put(model.getId(), model);
        }

        List<Long> keptIds = new ArrayList<>();
        for (ModelRequest request : requests) {
            if (request == null || trimToNull(request.getModelName()) == null) {
                throw new IllegalArgumentException("模型名称不能为空");
            }
            String modelName = trimToNull(request.getModelName());
            McModel target = request.getId() == null ? null : existingById.get(request.getId());
            if (request.getId() != null && target == null) {
                throw new IllegalArgumentException("模型不存在或不属于该供应商：" + request.getId());
            }
            Long excludeId = target == null ? null : target.getId();
            McModel duplicate = store.findModelByProviderAndName(provider.getId(), modelName, excludeId);
            if (duplicate != null) {
                throw new IllegalStateException("同一供应商下模型名称重复：" + modelName);
            }

            boolean isNew = target == null;
            McModel model = isNew ? new McModel() : target;
            model.setProviderId(provider.getId());
            model.setModelName(modelName);
            model.setDisplayName(trimToNull(request.getDisplayName()) == null
                    ? modelName : trimToNull(request.getDisplayName()));
            model.setReasoningLevels(writeReasoningLevels(request.getReasoningLevels()));
            model.setStatus(request.getStatus() == null ? 1 : request.getStatus());
            model.setContextWindow(resolveTokens(provider, modelName, request.getContextWindow(), true));
            model.setMaxOutputTokens(resolveTokens(provider, modelName, request.getMaxOutputTokens(), false));
            model.setInputTypes(joinInputTypes(provider, modelName, request.getInputTypes()));
            if (model.getStatus() != 0 && model.getStatus() != 1) {
                throw new IllegalArgumentException("模型状态只能是 1（启用）或 0（禁用）");
            }

            if (isNew) {
                store.insertModel(model);
            } else {
                store.updateModel(model);
            }
            keptIds.add(model.getId());
        }

        for (McModel model : existing) {
            if (!keptIds.contains(model.getId())) {
                store.deleteModel(model.getId());
            }
        }
    }

    /**
     * 解析窗口数值：显式简写值（1M / 200K）优先；未填时按目录兜底（供应商 /models 字段 → models.dev）；
     * 仍未知则为 0（前端按「未知」展示，可后续手填或走 enrich 补全）
     */
    private Integer resolveTokens(McProvider provider, String modelName, String rawValue, boolean contextWindow) {
        Integer explicit = TokenSize.parse(rawValue);
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        RemoteModelInfo probe = new RemoteModelInfo();
        probe.setId(modelName);
        metadataCatalog.fillMissing(provider.getType(), probe);
        Integer fallback = contextWindow ? probe.getContextWindow() : probe.getMaxOutputTokens();
        return fallback == null ? 0 : fallback;
    }

    /**
     * 输入类型：显式多选优先；未选时按目录兜底（默认 text）
     */
    private String joinInputTypes(McProvider provider, String modelName, List<String> inputTypes) {
        List<String> values = new ArrayList<>();
        if (inputTypes != null) {
            for (String raw : inputTypes) {
                String value = trimToNull(raw);
                if (value == null) {
                    continue;
                }
                value = value.toLowerCase();
                if (!ALLOWED_INPUT_TYPES.contains(value)) {
                    throw new IllegalArgumentException("不支持的输入类型：" + raw + "（仅 text / image）");
                }
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            RemoteModelInfo probe = new RemoteModelInfo();
            probe.setId(modelName);
            metadataCatalog.fillMissing(provider.getType(), probe);
            if (probe.getInputTypes() != null) {
                values.addAll(probe.getInputTypes());
            }
        }
        if (values.isEmpty()) {
            values.add("text");
        }
        return String.join(",", values);
    }

    /** 推理档位序列化（去空 + key 唯一 + 兜底默认值） */
    private String writeReasoningLevels(List<ReasoningLevel> levels) {
        if (levels == null) {
            // 未提交该字段：默认档位不落库（读取时按默认展示）
            return null;
        }
        List<ReasoningLevel> normalized = new ArrayList<>();
        List<String> usedKeys = new ArrayList<>();
        for (ReasoningLevel level : levels) {
            if (level == null) {
                continue;
            }
            String key = trimToNull(level.getKey());
            String label = trimToNull(level.getLabel());
            if (key == null && label == null) {
                continue;
            }
            if (key == null) {
                key = label.toLowerCase();
            }
            if (usedKeys.contains(key)) {
                throw new IllegalArgumentException("推理档位 key 重复：" + key);
            }
            usedKeys.add(key);
            String apiValue = trimToNull(level.getApiValue());
            normalized.add(new ReasoningLevel(key, label == null ? key : label, apiValue == null ? "" : apiValue));
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(normalized);
    }

    /** 推理档位反序列化（空值 / 解析失败 → 默认档位） */
    private List<ReasoningLevel> readReasoningLevels(String json) {
        if (json == null || json.trim().isEmpty()) {
            return ProviderTypes.defaultReasoningLevels();
        }
        try {
            JSONArray array = JSON.parseArray(json);
            List<ReasoningLevel> levels = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JSONObject node = array.getJSONObject(i);
                if (node == null) {
                    continue;
                }
                levels.add(new ReasoningLevel(node.getString("key"), node.getString("label"), node.getString("apiValue")));
            }
            return levels.isEmpty() ? ProviderTypes.defaultReasoningLevels() : levels;
        } catch (RuntimeException e) {
            log.warn("[Model Config] reasoningLevels 解析失败，回落默认档位：{}", e.getMessage());
            return ProviderTypes.defaultReasoningLevels();
        }
    }

    private ProviderResponse toProviderResponse(McProvider provider, List<McModel> models) {
        ProviderResponse response = new ProviderResponse();
        response.setId(provider.getId());
        response.setName(provider.getName());
        response.setType(provider.getType());
        response.setBaseUrl(provider.getBaseUrl());
        String apiKey = provider.getApiKey();
        boolean hasApiKey = apiKey != null && !apiKey.trim().isEmpty();
        response.setHasApiKey(hasApiKey);
        response.setApiKeyHint(hasApiKey ? maskApiKey(apiKey) : null);
        response.setStatus(provider.getStatus());
        response.setRemark(provider.getRemark());
        response.setCreatedAt(provider.getCreatedAt());
        response.setUpdatedAt(provider.getUpdatedAt());

        List<ModelResponse> list = new ArrayList<>(models.size());
        for (McModel model : models) {
            list.add(toModelResponse(model, provider));
        }
        response.setModels(list);
        response.setModelCount(list.size());
        return response;
    }

    private ModelResponse toModelResponse(McModel model, McProvider provider) {
        ModelResponse response = new ModelResponse();
        response.setId(model.getId());
        response.setProviderId(model.getProviderId());
        response.setProviderName(provider == null ? null : provider.getName());
        response.setModelName(model.getModelName());
        response.setDisplayName(model.getDisplayName());
        response.setContextWindow(model.getContextWindow() == null ? 0 : model.getContextWindow());
        response.setMaxOutputTokens(model.getMaxOutputTokens() == null ? 0 : model.getMaxOutputTokens());
        response.setInputTypes(readInputTypes(model.getInputTypes()));
        response.setReasoningLevels(readReasoningLevels(model.getReasoningLevels()));
        response.setStatus(model.getStatus());
        response.setCreatedAt(model.getCreatedAt());
        response.setUpdatedAt(model.getUpdatedAt());
        return response;
    }

    private ModelOption toModelOption(McModel model) {
        ModelOption option = new ModelOption();
        option.setId(model.getId());
        option.setModelName(model.getModelName());
        option.setDisplayName(model.getDisplayName() == null || model.getDisplayName().trim().isEmpty()
                ? model.getModelName() : model.getDisplayName());
        option.setContextWindow(model.getContextWindow() == null ? 0 : model.getContextWindow());
        option.setMaxOutputTokens(model.getMaxOutputTokens() == null ? 0 : model.getMaxOutputTokens());
        option.setInputTypes(readInputTypes(model.getInputTypes()));
        option.setReasoningLevels(readReasoningLevels(model.getReasoningLevels()));
        return option;
    }

    private List<String> readInputTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.singletonList("text");
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = trimToNull(part);
            if (value != null) {
                values.add(value.toLowerCase());
            }
        }
        return values.isEmpty() ? Collections.singletonList("text") : values;
    }

    /** API Key 脱敏：保留首尾少量字符，中间固定省略 */
    private static String maskApiKey(String apiKey) {
        String value = apiKey.trim();
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 文本截断（测试回复回显用） */
    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    /**
     * 取异常的最内层消息（调用失败的根因通常在最内层，如连接拒绝 / 401 报文）
     */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = current.getClass().getSimpleName();
        }
        return abbreviate(message, 300);
    }

    // ------------------------------------------------------------
    // 聊天页选项 DTO（只服务「流式对话」页的模型选择器）

    /** 供应商分组 */
    public static class ModelGroup {
        private Long providerId;
        private String providerName;
        private String providerType;
        private List<ModelOption> models;

        public Long getProviderId() {
            return providerId;
        }

        public void setProviderId(Long providerId) {
            this.providerId = providerId;
        }

        public String getProviderName() {
            return providerName;
        }

        public void setProviderName(String providerName) {
            this.providerName = providerName;
        }

        public String getProviderType() {
            return providerType;
        }

        public void setProviderType(String providerType) {
            this.providerType = providerType;
        }

        public List<ModelOption> getModels() {
            return models;
        }

        public void setModels(List<ModelOption> models) {
            this.models = models;
        }
    }

    /** 可选模型 */
    public static class ModelOption {
        private Long id;
        private String modelName;
        private String displayName;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> inputTypes;
        private List<ReasoningLevel> reasoningLevels;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Integer getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(Integer contextWindow) {
            this.contextWindow = contextWindow;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public List<String> getInputTypes() {
            return inputTypes;
        }

        public void setInputTypes(List<String> inputTypes) {
            this.inputTypes = inputTypes;
        }

        public List<ReasoningLevel> getReasoningLevels() {
            return reasoningLevels;
        }

        public void setReasoningLevels(List<ReasoningLevel> reasoningLevels) {
            this.reasoningLevels = reasoningLevels;
        }
    }
}
