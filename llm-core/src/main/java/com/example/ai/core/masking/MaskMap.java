package com.example.ai.core.masking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 掩码映射表（掩码占位符 → 明文），仅存服务端，绝不序列化外发。
 * <p>
 * 采用确定性掩码：同一明文恒得同一占位符，重复出现不产生重复映射，
 * 会话重启后历史掩码与新读出的明文仍能对上。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class MaskMap {

    /** 掩码占位符 → 明文 */
    private final Map<String, String> placeholderToSecret = new LinkedHashMap<>();

    /**
     * 写入映射（已存在且值一致时保持幂等）
     */
    public synchronized void put(String placeholder, String secret) {
        placeholderToSecret.put(placeholder, secret);
    }

    /**
     * 查询已登记的明文；未登记返回 {@code null}
     */
    public synchronized String getSecret(String placeholder) {
        return placeholderToSecret.get(placeholder);
    }
}
