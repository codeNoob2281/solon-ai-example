package com.example.ai.model.support;

import java.util.Locale;

/**
 * token 数量简写解析 / 格式化（如 {@code 1M} / {@code 200K} / {@code 1.5m} / {@code 128000}）
 * <p>
 * 解析规则：允许数字后跟单位 {@code K}（×1024）或 {@code M}（×1024×1024），大小写不敏感、
 * 允许空格与 {@code tokens} 后缀；无单位按原始数量；非法输入返回 null（由调用方决定忽略或报错）。
 * <p>
 * 格式化规则（回显用）：能整除 1024^2 且 ≥1M 用 {@code NM}，能整除 1024 且 ≥1K 用 {@code NK}，
 * 否则用原值。
 */
public final class TokenSize {

    private static final long K = 1024L;
    private static final long M = 1024L * 1024L;

    private TokenSize() {
    }

    /**
     * 解析简写为 token 数
     *
     * @param text 形如 {@code 1M} / {@code 200K} / {@code 131072} / {@code 1.5M}；空串返回 null
     * @return 解析结果（&gt; 0）；非法返回 null
     */
    public static Integer parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT).replace(" ", "");
        // 去掉常见后缀：tokens / token / t
        if (lower.endsWith("tokens")) {
            lower = lower.substring(0, lower.length() - "tokens".length());
        } else if (lower.endsWith("token")) {
            lower = lower.substring(0, lower.length() - "token".length());
        } else if (lower.endsWith("t") && lower.length() > 1) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.isEmpty()) {
            return null;
        }

        long multiplier = 1L;
        char last = lower.charAt(lower.length() - 1);
        if (last == 'k') {
            multiplier = K;
            lower = lower.substring(0, lower.length() - 1);
        } else if (last == 'm') {
            multiplier = M;
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.isEmpty()) {
            return null;
        }

        double number;
        try {
            number = Double.parseDouble(lower);
        } catch (NumberFormatException e) {
            return null;
        }
        if (number <= 0) {
            return null;
        }
        long tokens = Math.round(number * multiplier);
        if (tokens <= 0 || tokens > Integer.MAX_VALUE) {
            return null;
        }
        return (int) tokens;
    }

    /**
     * 解析简写（带兜底）：解析失败或为空时返回 fallback
     */
    public static Integer parseOrDefault(String text, Integer fallback) {
        Integer parsed = parse(text);
        return parsed == null ? fallback : parsed;
    }

    /**
     * 回显格式化：131072 → {@code 128K}；1048576 → {@code 1M}；1000 → {@code 1000}；null/0 → 空串
     */
    public static String format(Integer tokens) {
        if (tokens == null || tokens <= 0) {
            return "";
        }
        long value = tokens;
        if (value >= M && value % M == 0) {
            return (value / M) + "M";
        }
        if (value >= K && value % K == 0) {
            return (value / K) + "K";
        }
        return String.valueOf(value);
    }
}
