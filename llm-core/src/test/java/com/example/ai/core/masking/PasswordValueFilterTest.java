package com.example.ai.core.masking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordValueFilter} 单测
 */
class PasswordValueFilterTest {

    private static String ph(String label, String hash) {
        return "{{" + label + "_" + hash + "}}";
    }

    @Test
    void normalPasswordShouldPass() {
        assertTrue(PasswordValueFilter.isValid("Alpha123x99"));
        assertTrue(PasswordValueFilter.isValid("Bravo456y88"));
    }

    @Test
    void tooShortOrTooLongShouldReject() {
        assertFalse(PasswordValueFilter.isValid("abc"));
        assertFalse(PasswordValueFilter.isValid(repeat('a', 65)));
    }

    @Test
    void blacklistShouldReject() {
        assertFalse(PasswordValueFilter.isValid("password"));
        assertFalse(PasswordValueFilter.isValid("EXAMPLE"));
        assertFalse(PasswordValueFilter.isValid("token"));
        assertFalse(PasswordValueFilter.isValid("请输入"));
    }

    @Test
    void placeholderPrefixShouldReject() {
        assertFalse(PasswordValueFilter.isValid("${DB_PASSWORD}"));
        assertFalse(PasswordValueFilter.isValid(ph("PASSWORD", "a1b2c3d4e5f60718")));
        assertFalse(PasswordValueFilter.isValid("#{cache.key}"));
    }

    @Test
    void nullShouldReject() {
        assertFalse(PasswordValueFilter.isValid(null));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
