package com.example.ai.core.masking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogMasker} 日志脱敏单测
 */
class LogMaskerTest {

    private static String open(String label) {
        return "{{" + label + "_";
    }

    @Test
    void nullShouldPassThrough() {
        assertNull(LogMasker.mask(null));
    }

    @Test
    void apiKeyShouldBeMaskedInLogs() {
        String key = "sk-" + "abc123XYZ";
        String masked = LogMasker.mask(key);

        assertFalse(masked.contains(key));
        assertTrue(masked.startsWith(open("API_KEY_SECRET")));
    }

    @Test
    void passwordShouldBeMaskedInLogs() {
        String value = "Passw0rdAbc";
        String masked = LogMasker.mask("password = " + value);

        assertFalse(masked.contains(value));
        assertTrue(masked.contains("password = " + open("PASSWORD")));
    }
}