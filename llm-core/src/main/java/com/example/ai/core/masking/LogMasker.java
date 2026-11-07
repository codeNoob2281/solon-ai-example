package com.example.ai.core.masking;

/**
 * 日志脱敏工具
 * <p>
 * 供日志拦截器在打印请求/响应/用户消息前调用：复用 {@link SecretMasker} 的内置规则
 * （API Key、身份证、手机号、邮箱、银行卡、车牌、IP、密码等）把敏感明文替换为占位符，
 * 避免日志泄露。日志场景不需要还原，故每次使用独立的 {@link MaskMap}，不跨日志累积。
 * <p>
 * 注意：与消息脱敏开关（{@code ai.chat.masking.enabled}）无关，日志脱敏始终生效。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public final class LogMasker {

    /** 日志专用掩码器（内置规则，复用同一实例） */
    private static final SecretMasker MASKER = new SecretMasker(new MaskingProperties());

    private LogMasker() {
    }

    /**
     * 掩码文本中的敏感信息；null 原样返回
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        return MASKER.mask(text, new MaskMap());
    }
}
