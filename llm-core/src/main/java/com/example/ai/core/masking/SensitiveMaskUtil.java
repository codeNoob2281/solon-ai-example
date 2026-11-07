package com.example.ai.core.masking;

import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息掩码工具（自实现，仅做可逆掩码）
 * <p>
 * 生成确定性占位符 {@code {{<label>_<hash>}}} 并在 {@link MaskMap} 掩码字典中登记
 * 「占位符 → 明文」，从而支持同一明文恒得同一占位符、以及后续还原。
 * <p>
 * 仅依赖 hutool-core（{@link HashUtil} 生成确定性摘要、{@link StrUtil} 做空值判断）。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public final class SensitiveMaskUtil {

    /** 摘要十六进制长度（64 位摘要 = 16 个十六进制字符） */
    private static final int HASH_HEX_LENGTH = 16;
    /** 碰撞时追加的次级摘要长度 */
    private static final int SECONDARY_HASH_HEX_LENGTH = 8;

    private SensitiveMaskUtil() {
    }

    /**
     * 掩码单个敏感值：生成确定性占位符并登记到掩码字典
     *
     * @param label     掩码标签（如 API_KEY_SECRET）
     * @param plaintext 明文
     * @param dictionary 掩码字典
     * @return 占位符；明文为空时原样返回
     */
    public static String mask(String label, String plaintext, MaskMap dictionary) {
        if (StrUtil.isEmpty(plaintext)) {
            return plaintext;
        }
        String hash = digest(plaintext, HASH_HEX_LENGTH);
        String placeholder = "{{" + label + "_" + hash + "}}";
        String existing = dictionary.getSecret(placeholder);
        if (existing != null && !existing.equals(plaintext)) {
            // 64 位摘要碰撞（极罕见）：追加次级摘要，保证占位符唯一
            hash = hash + digest(plaintext, SECONDARY_HASH_HEX_LENGTH,
                    String.format("%08x", HashUtil.fnvHash(plaintext)));
            placeholder = "{{" + label + "_" + hash + "}}";
        }
        dictionary.put(placeholder, plaintext);
        return placeholder;
    }

    /**
     * 还原文本：占位符替换回明文；任一占位符在字典中缺失则 fail-closed 抛异常
     */
    public static String unmask(String text, MaskMap dictionary, Pattern placeholderPattern) {
        if (StrUtil.isEmpty(text)) {
            return text;
        }
        Matcher matcher = placeholderPattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String placeholder = matcher.group();
            String secret = dictionary.getSecret(placeholder);
            if (secret == null) {
                throw new MaskingException("无法还原掩码（映射表缺失）: " + placeholder);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(secret));
        }
        if (found) {
            matcher.appendTail(sb);
            return sb.toString();
        }
        return text;
    }

    /**
     * 构建占位符识别正则（只匹配已登记的标签）
     */
    public static Pattern placeholderPattern(Collection<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String label : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(label));
        }
        return Pattern.compile("\\{\\{(?:" + sb + ")_[0-9a-f]{8," + HASH_HEX_LENGTH
                + SECONDARY_HASH_HEX_LENGTH + "}\\}\\}");
    }

    /**
     * 确定性摘要（十六进制小写）
     */
    private static String digest(String plaintext, int hexLength) {
        String hex = String.format("%016x", HashUtil.mixHash(plaintext));
        return hex.substring(0, hexLength);
    }

    /**
     * 带盐摘要：secondary 为额外盐串，用于碰撞消歧
     */
    private static String digest(String plaintext, int hexLength, String salt) {
        String hex = String.format("%016x", HashUtil.mixHash(salt + plaintext));
        return hex.substring(0, hexLength);
    }
}
