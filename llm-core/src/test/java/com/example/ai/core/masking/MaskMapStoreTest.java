package com.example.ai.core.masking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link MaskMapStore} 会话隔离单测
 */
class MaskMapStoreTest {

    @Test
    void differentSessionsShouldBeIsolated() {
        MaskMapStore store = new MaskMapStore();

        store.get("session-a").put("ph-a-1", "secret-a");
        store.get("session-b").put("ph-b-1", "secret-b");

        assertEquals("secret-a", store.get("session-a").getSecret("ph-a-1"));
        assertNull(store.get("session-a").getSecret("ph-b-1"));
        assertEquals("secret-b", store.get("session-b").getSecret("ph-b-1"));
    }

    @Test
    void sameSessionShouldShareInstance() {
        MaskMapStore store = new MaskMapStore();

        assertSame(store.get("session-x"), store.get("session-x"));
    }

    @Test
    void nullSessionShouldFallbackToThreadLocal() {
        MaskMapStore store = new MaskMapStore();

        assertSame(store.get(null), store.get(""));
    }
}
