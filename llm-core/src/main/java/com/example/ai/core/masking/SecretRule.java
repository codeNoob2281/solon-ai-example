package com.example.ai.core.masking;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 敏感信息识别规则
 * <p>
 * 每条规则由「掩码标签 + 正则」组成：命中正则的明文会被替换为
 * {@code {{<label>_<hash>}}} 形式的掩码占位符，标签同时用于后续还原时识别占位符。
 * <p>
 * 默认掩码整个匹配（{@code valueGroup=0}）；对「键=值」这类需要保留上下文的结构（如密码），
 * 可指定只掩码某个捕获组，并附带值过滤器（如排除占位符 / 常见非密码词）。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class SecretRule {

    /** 掩码标签（大写字母/数字/下划线），会参与占位符命名，如 API_KEY_SECRET */
    private final String label;

    /** 敏感明文识别正则 */
    private final Pattern pattern;

    /** 需要掩码的捕获组下标（0 表示整个匹配） */
    private final int valueGroup;

    /** 值过滤器：返回 false 的候选值不掩码（可空） */
    private final Predicate<String> valueFilter;

    public SecretRule(String label, String regex) {
        this(label, regex, 0, null);
    }

    public SecretRule(String label, String regex, int valueGroup, Predicate<String> valueFilter) {
        this.label = normalizeLabel(label);
        this.pattern = Pattern.compile(regex);
        this.valueGroup = valueGroup;
        this.valueFilter = valueFilter;
    }

    public String getLabel() {
        return label;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public int getValueGroup() {
        return valueGroup;
    }

    public Predicate<String> getValueFilter() {
        return valueFilter;
    }

    /**
     * 标签归一化：仅保留字母数字，其余替换为下划线并转大写，保证可作为占位符片段。
     */
    private static String normalizeLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("SecretRule label 不能为空");
        }
        String normalized = label.trim().toUpperCase().replaceAll("[^A-Z0-9]", "_");
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("SecretRule label 非法（须以字母开头）: " + label);
        }
        return normalized;
    }
}
