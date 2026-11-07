package com.example.ai.core.masking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息脱敏配置（前缀 ai.chat.masking）
 * <p>
 * 阶段一：进模型侧掩码化闭环——工具响应掩码、工具参数还原、请求消息兜底掩码。
 */
@Data
@ConfigurationProperties(prefix = "ai.chat.masking")
public class MaskingProperties {

    /** 是否启用消息脱敏（总开关，默认关闭，避免未显式开启时改变模型可见内容） */
    private boolean enabled = false;

    /**
     * 自定义敏感信息识别规则（可选）。
     * <p>内置已包含 sk- API Key 及各 {@link DesensitizedType} 中可自动识别的类型；
     * 此处按标签覆盖内置规则，或追加新的类型规则。</p>
     */
    private List<Rule> rules = new ArrayList<>();

    /**
     * 规则配置项
     */
    @Data
    public static class Rule {
        /** 掩码标签，如 API_KEY_SECRET */
        private String label;
        /** 识别正则 */
        private String regex;
    }
}
