package com.example.ai.model.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.ai.model.dto.ProviderTestResult;
import com.example.ai.model.dto.RemoteModelInfo;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容供应商 HTTP 客户端（只做「模型列表」与「连通性测试」两类只读调用）
 * <p>
 * 端点拼接与 Solon AI 的 OpenAI 方言保持一致：Base URL 已带版本段（/v1、/v4 等）
 * 直接补 {@code /models}，否则补 {@code /v1/models}；已指向 {@code /models} 的原样使用。
 * <p>
 * 说明：不复用 {@code ChatModel} 做探测——{@code /models} 是配置页需要的原始模型清单，
 * 且探测不能依赖模型名有效（连不上与模型名错误是两回事）。
 */
@Slf4j
@Component
public class ProviderApiClient {

    /** 连通性测试 / 列表拉取的超时秒数（配置页同步请求，不宜过长） */
    private static final int TIMEOUT_SECONDS = 20;

    /** 响应体摘要截断长度（错误提示展示用） */
    private static final int ERROR_BODY_MAX = 300;

    @Resource
    private ModelMetadataCatalog metadataCatalog;

    /**
     * 拉取供应商远程模型列表（含元信息补全：供应商字段优先，缺失回落 models.dev 目录）
     *
     * @param type    供应商类型（决定 models.dev 目录优先级）
     * @param baseUrl 接口地址
     * @param apiKey  API Key（可空，Ollama 等本地服务无需）
     */
    public List<RemoteModelInfo> listModels(String type, String baseUrl, String apiKey) {
        HttpResult result = get(buildModelsUrl(baseUrl), apiKey);
        if (!result.successful()) {
            throw new IllegalStateException("获取模型列表失败：" + result.errorSummary());
        }
        List<RemoteModelInfo> models = parseModels(result.body);
        for (RemoteModelInfo model : models) {
            metadataCatalog.fillMissing(type, model);
        }
        return models;
    }

    /**
     * 连通性测试：仅验证「供应商可达 + 凭据有效」，不校验任何具体模型
     */
    public ProviderTestResult testConnection(String type, String baseUrl, String apiKey) {
        long start = System.currentTimeMillis();
        try {
            HttpResult result = get(buildModelsUrl(baseUrl), apiKey);
            long elapsed = System.currentTimeMillis() - start;
            if (!result.successful()) {
                return new ProviderTestResult(false, result.errorSummary(), elapsed, 0);
            }
            int count = parseModels(result.body).size();
            return new ProviderTestResult(true, "连通正常，共 " + count + " 个模型", elapsed, count);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[Model Config] provider test failed: baseUrl={}, cause={}", baseUrl, e.getMessage());
            return new ProviderTestResult(false, "连接异常：" + e.getMessage(), elapsed, 0);
        }
    }

    // ------------------------------------------------------------
    // 内部实现

    /** 端点地址：已带 /models 原样；已带版本段补 /models；否则补 /v1/models */
    static String buildModelsUrl(String baseUrl) {
        String url = trimTrailingSlash(baseUrl);
        if (url.isEmpty()) {
            throw new IllegalArgumentException("供应商接口地址不能为空");
        }
        if (url.endsWith("/models")) {
            return url;
        }
        if (VERSION_SEGMENT.matcher(url).find()) {
            return url + "/models";
        }
        return url + "/v1/models";
    }

    /** 版本段匹配（/v1、/v4 等，与 Solon AI OpenAI 方言同规则） */
    private static final java.util.regex.Pattern VERSION_SEGMENT =
            java.util.regex.Pattern.compile("/v\\d+$");

    private HttpResult get(String url, String apiKey) {
        HttpUtils http = HttpUtils.http(url)
                .timeout(TIMEOUT_SECONDS)
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            http = http.header("Authorization", "Bearer " + apiKey.trim());
        }
        try (HttpResponse response = http.exec("GET")) {
            String body = response.bodyAsString();
            return new HttpResult(response.code(), body);
        } catch (Exception e) {
            throw new IllegalStateException("请求供应商接口失败：" + e.getMessage(), e);
        }
    }

    /** 解析 /models 响应：{data:[{id, owned_by, ...}]} */
    private List<RemoteModelInfo> parseModels(String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.emptyList();
        }
        JSONObject root;
        try {
            root = JSONObject.parseObject(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("供应商返回内容不是合法 JSON", e);
        }
        JSONArray data = root.getJSONArray("data");
        if (data == null) {
            // 部分服务（Ollama 原生 /api/tags 风格）用 models 字段
            data = root.getJSONArray("models");
        }
        List<RemoteModelInfo> models = new ArrayList<>();
        if (data == null) {
            return models;
        }
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = firstNonBlank(item.getString("id"), item.getString("name"), item.getString("model"));
            if (id == null) {
                continue;
            }
            RemoteModelInfo info = new RemoteModelInfo();
            info.setId(id);
            info.setOwnedBy(item.getString("owned_by"));
            // 少数网关（OpenRouter 等）在 /models 里直接给出窗口信息，优先采信
            info.setContextWindow(firstPositiveInt(item,
                    "context_length", "context_window", "max_context_length", "max_input_tokens"));
            info.setMaxOutputTokens(firstPositiveInt(item,
                    "max_completion_tokens", "max_output_tokens"));
            models.add(info);
        }
        return models;
    }

    /** 依次取第一个为正整数的字段值（缺失 / 非数字 / ≤0 返回 null） */
    private static Integer firstPositiveInt(JSONObject item, String... keys) {
        for (String key : keys) {
            Integer value = item.getInteger(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** HTTP 响应包装（只保留判定与摘要所需信息） */
    private static final class HttpResult {
        private final int code;
        private final String body;

        private HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        private boolean successful() {
            return code >= 200 && code < 300;
        }

        private String errorSummary() {
            String head = "HTTP " + code;
            if (body == null || body.trim().isEmpty()) {
                return head + "（响应为空）";
            }
            String text = body.trim();
            if (text.length() > ERROR_BODY_MAX) {
                text = text.substring(0, ERROR_BODY_MAX) + "...";
            }
            return head + "：" + text;
        }
    }
}
