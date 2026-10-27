package com.example.ai.model.support;

import com.example.ai.model.dto.ReasoningLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商类型预设（默认 Base URL 提示 + models.dev 目录对齐用）
 * <p>
 * 仅用于前端表单的「选择类型自动填 Base URL」提示与元信息回落的 provider 优先匹配；
 * 实际调用一律走 OpenAI 兼容协议（Solon AI 的 openai 方言会在 Base URL 缺少版本段时补 /v1）。
 */
public final class ProviderTypes {

    /** 类型 → 默认 Base URL */
    private static final Map<String, String> DEFAULT_BASE_URLS;

    /** 类型 → 展示名 */
    private static final Map<String, String> LABELS;

    static {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("openai", "https://api.openai.com/v1");
        urls.put("deepseek", "https://api.deepseek.com/v1");
        urls.put("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        urls.put("moonshot", "https://api.moonshot.cn/v1");
        urls.put("zhipu", "https://open.bigmodel.cn/api/paas/v4");
        urls.put("ollama", "http://localhost:11434/v1");
        urls.put("custom", "");
        DEFAULT_BASE_URLS = Collections.unmodifiableMap(urls);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("openai", "OpenAI");
        labels.put("deepseek", "DeepSeek");
        labels.put("qwen", "通义千问（DashScope 兼容模式）");
        labels.put("moonshot", "Kimi（Moonshot）");
        labels.put("zhipu", "智谱 GLM");
        labels.put("ollama", "本地 Ollama");
        labels.put("custom", "自定义（其它 OpenAI 兼容服务）");
        LABELS = Collections.unmodifiableMap(labels);
    }

    /** 已知类型列表（顺序即前端下拉顺序） */
    public static final List<String> KNOWN_TYPES =
            Collections.unmodifiableList(new ArrayList<>(DEFAULT_BASE_URLS.keySet()));

    private ProviderTypes() {
    }

    /** 类型规范化：未知 / 空 → custom */
    public static String normalize(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "custom";
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        return DEFAULT_BASE_URLS.containsKey(value) ? value : "custom";
    }

    /** 默认 Base URL（未知类型返回空串） */
    public static String defaultBaseUrl(String type) {
        String baseUrl = DEFAULT_BASE_URLS.get(normalize(type));
        return baseUrl == null ? "" : baseUrl;
    }

    /** 展示名（未知类型原样返回） */
    public static String label(String type) {
        String label = LABELS.get(normalize(type));
        return label == null ? type : label;
    }

    /**
     * 默认推理档位（模型未配置档位时）：off / low / medium / high。
     * <p>{@code apiValue} 为下发给供应商的参数值：{@code off} 表示请求关闭思考
     * （由 Solon AI 按模型映射，未知模型不写出该开关），其余作为 {@code reasoning_effort} 取值；
     * 空串表示「不指定」（保持供应商默认行为）。用户可在模型配置里改写 apiValue 覆盖默认映射。
     */
    public static List<ReasoningLevel> defaultReasoningLevels() {
        return Arrays.asList(
                new ReasoningLevel("off", "关闭", "off"),
                new ReasoningLevel("low", "低", "low"),
                new ReasoningLevel("medium", "中", "medium"),
                new ReasoningLevel("high", "高", "high"));
    }
}
