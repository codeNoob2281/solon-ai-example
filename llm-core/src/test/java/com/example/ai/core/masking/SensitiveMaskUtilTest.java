package com.example.ai.core.masking;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SensitiveMaskUtil} 可逆掩码单测
 */
class SensitiveMaskUtilTest {

    private static String ph(String label, String hash) {
        return "{{" + label + "_" + hash + "}}";
    }

    private static String open(String label) {
        return "{{" + label + "_";
    }

    @Test
    void maskThenUnmaskShouldRoundTrip() {
        MaskMap dictionary = new MaskMap();
        String plaintext = "sk-" + "abc123XYZ";

        String placeholder = SensitiveMaskUtil.mask("API_KEY_SECRET", plaintext, dictionary);
        Pattern pattern = SensitiveMaskUtil.placeholderPattern(Collections.singletonList("API_KEY_SECRET"));

        assertTrue(placeholder.startsWith(open("API_KEY_SECRET")));
        assertEquals(plaintext, dictionary.getSecret(placeholder));
        assertEquals(plaintext, SensitiveMaskUtil.unmask(placeholder, dictionary, pattern));
    }

    @Test
    void maskShouldBeDeterministic() {
        MaskMap dictionary = new MaskMap();

        String first = SensitiveMaskUtil.mask("MOBILE_PHONE", "18049531999", dictionary);
        String second = SensitiveMaskUtil.mask("MOBILE_PHONE", "18049531999", dictionary);

        assertEquals(first, second);
    }

    @Test
    void emptyValueShouldStayAsIs() {
        MaskMap dictionary = new MaskMap();

        assertEquals("", SensitiveMaskUtil.mask("PASSWORD", "", dictionary));
        assertEquals(null, SensitiveMaskUtil.mask("PASSWORD", null, dictionary));
    }

    @Test
    void unmaskUnknownPlaceholderShouldFailClosed() {
        MaskMap dictionary = new MaskMap();
        Pattern pattern = SensitiveMaskUtil.placeholderPattern(Collections.singletonList("API_KEY_SECRET"));

        assertThrows(MaskingException.class,
                () -> SensitiveMaskUtil.unmask(ph("API_KEY_SECRET", "0123456789abcdef"), dictionary, pattern));
    }

    @Test
    void placeholderPatternShouldOnlyMatchRegisteredLabels() {
        MaskMap dictionary = new MaskMap();
        Pattern emailPattern = SensitiveMaskUtil.placeholderPattern(Collections.singletonList("EMAIL"));

        String apiKeyPlaceholder = ph("API_KEY_SECRET", "0123456789abcdef");
        // 非 EMAIL 标签不应被 EMAIL 的占位符正则命中，原样返回
        assertEquals(apiKeyPlaceholder, SensitiveMaskUtil.unmask(apiKeyPlaceholder, dictionary, emailPattern));
    }

    @Test
    void placeholderPatternShouldSupportMultipleLabels() {
        MaskMap dictionary = new MaskMap();
        Pattern pattern = SensitiveMaskUtil.placeholderPattern(Arrays.asList("EMAIL", "MOBILE_PHONE"));

        String placeholder = SensitiveMaskUtil.mask("MOBILE_PHONE", "18049531999", dictionary);
        assertTrue(pattern.matcher(placeholder).matches());
        assertFalse(pattern.matcher(ph("OTHER", "0123456789abcdef")).matches());
    }
}
