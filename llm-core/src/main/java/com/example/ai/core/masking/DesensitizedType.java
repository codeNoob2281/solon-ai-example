package com.example.ai.core.masking;

import lombok.Getter;

import java.util.function.Predicate;

/**
 * 支持的脱敏类型枚举（参照 Hutool {@code DesensitizedUtil.DesensitizedType}，并补充 sk- API Key）
 * <p>
 * 每个类型可携带用于「自动识别」的正则；无可靠正则的类型（如用户id、中文名、地址等）
 * 正则为 {@code null}，不参与内置脱敏规则，仅作类型占位。
 * <p>
 * 密码无法单靠长度正则识别，改为「键=值」上下文正则（只掩码捕获组 1），并用
 * {@link PasswordValueFilter} 剔除占位符/常见非密码词。
 *
 * @author huang zifan
 * @since 1.0.0
 */
@Getter
public enum DesensitizedType {
    /** sk- 类 API Key（OpenAI 及兼容协议；前置非字母数字避免误伤 task-/risk- 等普通词） */
    API_KEY_SECRET("(?<![A-Za-z0-9])sk-[A-Za-z0-9_\\-]{3,}"),
    /** 用户id（过于宽泛，不做自动识别） */
    USER_ID(null),
    /** 中文名（无可靠正则，不做自动识别） */
    CHINESE_NAME(null),
    /** 身份证号 */
    ID_CARD("(?<![0-9A-Za-z])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])"),
    /** 座机号 */
    FIXED_PHONE("(?<!\\d)0\\d{2,3}-?\\d{7,8}(?!\\d)"),
    /** 手机号 */
    MOBILE_PHONE("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
    /** 地址（无可靠正则，不做自动识别） */
    ADDRESS(null),
    /** 电子邮件 */
    EMAIL("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
    /**
     * 密码：中文/英文「键=值」上下文匹配，只掩码值（捕获组 1）；
     * 排除 密码学/密码锁/密码策略/忘记密码 等，值过滤器见 {@link PasswordValueFilter}。
     */
    PASSWORD("(?i)(?:\\b(?:password|passwd|pwd)\\b\\s*[:=]\\s*[\"']?"
            + "|(?:密码|口令)(?!学|锁|策略|强度|重置|修改|忘记|输入|框|管理|安全|哈希|加密)"
            + "\\s*(?:是|为|[:：=])\\s*[:：=]?\\s*[“\"']?)"
            + "([^\\s\"'#;，。；：:\\p{IsHan}]{6,64})", 1, PasswordValueFilter::isValid),
    /** 中国大陆车牌，包含普通车辆、新能源车辆 */
    CAR_LICENSE("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领A-Z][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]"),
    /** 银行卡 */
    BANK_CARD("(?<!\\d)\\d{16,19}(?!\\d)"),
    /** IPv4地址 */
    IPV4("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(?![\\d.])"),
    /** IPv6地址（完整 8 段形态） */
    IPV6("(?<![:\\w])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?![:\\w])");

    /** 自动识别正则（可空） */
    private final String regex;

    /** 需要掩码的捕获组下标（0 表示整个匹配） */
    private final int valueGroup;

    /** 值过滤器：返回 false 的候选值不掩码（可空） */
    private final Predicate<String> valueFilter;

    DesensitizedType(String regex) {
        this(regex, 0, null);
    }

    DesensitizedType(String regex, int valueGroup, Predicate<String> valueFilter) {
        this.regex = regex;
        this.valueGroup = valueGroup;
        this.valueFilter = valueFilter;
    }
}
