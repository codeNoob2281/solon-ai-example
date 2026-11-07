package com.example.ai.core.masking;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 密码候选值过滤器
 * <p>
 * 密码无法仅凭长度正则识别（会误伤普通单词），因此先通过「键=值」上下文正则定位候选，
 * 再用本过滤器剔除：占位符/变量引用、常见非密码词、长度不符的值。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public final class PasswordValueFilter {

    /** 占位符/变量引用前缀，命中即丢弃 */
    private static final String[] PLACEHOLDER_PREFIX = {"${", "#{", "{{", "<%", "$(", "%{"};

    /** 常见非密码词，命中即丢弃 */
    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "password", "passwd", "pwd", "required", "example", "sample",
            "changeme", "none", "null", "your_password", "yourpassword",
            "secret", "token", "string", "请输入", "示例"
    ));

    private PasswordValueFilter() {
    }

    /**
     * 判断候选值是否可视为密码
     */
    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.length() < 6 || v.length() > 64) {
            return false;
        }
        if (BLACKLIST.contains(v.toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (String prefix : PLACEHOLDER_PREFIX) {
            if (v.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
