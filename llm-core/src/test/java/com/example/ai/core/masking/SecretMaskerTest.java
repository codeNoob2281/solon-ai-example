package com.example.ai.core.masking;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SecretMasker} 脱敏引擎单测（内置规则 / 密码上下文 / 消息与工具结果 / 还原）
 */
class SecretMaskerTest {

    private static final String SK = "sk-" + "abc123XYZ";

    private static String ph(String label, String hash) {
        return "{{" + label + "_" + hash + "}}";
    }

    private static String open(String label) {
        return "{{" + label + "_";
    }

    private final SecretMasker masker = new SecretMasker(new MaskingProperties());

    @Test
    void apiKeyShouldBeMasked() {
        assertMaskedAndRestorable(SK, "API_KEY_SECRET");
    }

    @Test
    void mobilePhoneShouldBeMasked() {
        assertMaskedAndRestorable("18049531999", "MOBILE_PHONE");
    }

    @Test
    void emailShouldBeMasked() {
        assertMaskedAndRestorable("duandazhi-jack@gmail.com.cn", "EMAIL");
    }

    @Test
    void idCardShouldBeMasked() {
        assertMaskedAndRestorable("51343620000320711X", "ID_CARD");
    }

    @Test
    void ipv4ShouldBeMasked() {
        assertMaskedAndRestorable("192.168.1.1", "IPV4");
    }

    @Test
    void bankCardShouldBeMasked() {
        assertMaskedAndRestorable("6222021234567890123", "BANK_CARD");
    }

    @Test
    void carLicenseShouldBeMasked() {
        assertMaskedAndRestorable("苏D40000", "CAR_LICENSE");
    }

    @Test
    void fixedPhoneShouldBeMasked() {
        assertMaskedAndRestorable("09157518479", "FIXED_PHONE");
    }

    @Test
    void ordinaryWordsShouldNotBeMasked() {
        MaskMap dictionary = new MaskMap();
        String text = "task-manager risk-free passwordless login enabled password is required 密码策略：至少8位";

        assertEquals(text, masker.mask(text, dictionary));
    }

    @Test
    void passwordShouldMaskValueOnlyAndKeepKey() {
        MaskMap dictionary = new MaskMap();
        String text = "password = Alpha123x99\n"
                + "数据库密码是 Bravo456y88\n"
                + "登录口令为 Delta789z77\n"
                + "密码策略：至少8位\n"
                + "忘记密码怎么办\n"
                + "password is required\n"
                + "pwd: ${DB_PASSWORD}\n"
                + "passwordless login enabled\n"
                + "password = example\n";

        String masked = masker.mask(text, dictionary);

        assertEquals(3, countOccurrences(masked, open("PASSWORD")));
        assertTrue(masked.contains("password = " + open("PASSWORD")));
        assertTrue(masked.contains("password = example"));
        assertTrue(masked.contains("密码策略：至少8位"));
        assertTrue(masked.contains("password is required"));
        assertTrue(masked.contains("passwordless login enabled"));
        assertTrue(masked.contains("${DB_PASSWORD}"));
        assertEquals(text, masker.unmask(masked, dictionary));
        // 幂等：对已掩码文本再次掩码不改变
        assertEquals(masked, masker.mask(masked, dictionary));
    }

    @Test
    void passwordWithDoubleSeparatorShouldMaskValueOnly() {
        MaskMap dictionary = new MaskMap();
        String text = "我的密码是：" + "Passw0rdAbc1" + "\n"
                + "密码：" + "Qz8Lm3Nv7Kj" + "\n";

        String masked = masker.mask(text, dictionary);

        assertTrue(masked.contains("我的密码是：" + open("PASSWORD")));
        assertTrue(masked.contains("密码：" + open("PASSWORD")));
        assertFalse(masked.contains("Passw0rdAbc1"));
        assertFalse(masked.contains("Qz8Lm3Nv7Kj"));
        assertEquals(text, masker.unmask(masked, dictionary));
    }

    @Test
    void unmaskArgsShouldRestoreNestedValues() {
        MaskMap dictionary = new MaskMap();
        String placeholder = masker.mask(SK, dictionary);

        Map<String, Object> args = new HashMap<>();
        args.put("content", "write " + placeholder);
        Map<String, Object> nested = new HashMap<>();
        nested.put("v", placeholder);
        args.put("nested", nested);
        List<Object> list = new ArrayList<>();
        list.add(placeholder);
        args.put("list", list);

        masker.unmaskArgs(args, dictionary);

        assertEquals("write " + SK, args.get("content"));
        assertEquals(SK, nested.get("v"));
        assertEquals(SK, list.get(0));
    }

    @Test
    void maskToolResultShouldMaskTextAndKeepErrorFlag() {
        MaskMap dictionary = new MaskMap();
        ToolResult result = ToolResult.error("key " + SK);

        ToolResult masked = masker.maskToolResult(result, dictionary);

        assertTrue(masked.isError());
        assertTrue(masked.getContent().contains(open("API_KEY_SECRET")));
    }

    @Test
    void maskPromptShouldRebuildWithoutPollutingOriginal() {
        MaskMap dictionary = new MaskMap();
        UserMessage original = ChatMessage.ofUser("tel 18049531999");
        Prompt prompt = Prompt.of(original);

        masker.maskPrompt(prompt, dictionary);

        assertNotSame(original, prompt.getMessages().get(0));
        assertTrue(prompt.getMessages().get(0).getContent().contains(open("MOBILE_PHONE")));
        // 原消息对象不被污染
        assertEquals("tel 18049531999", original.getContent());
    }

    @Test
    void maskPromptShouldPreserveToolMessageIdentity() {
        MaskMap dictionary = new MaskMap();
        ToolMessage original = ChatMessage.ofTool(new ToolResult("key " + SK), "read", "call-1", false);
        Prompt prompt = Prompt.of(original);

        masker.maskPrompt(prompt, dictionary);

        ToolMessage masked = (ToolMessage) prompt.getMessages().get(0);
        assertEquals("read", masked.getName());
        assertEquals("call-1", masked.getToolCallId());
        assertTrue(masked.getContent().contains(open("API_KEY_SECRET")));
        // 原消息对象不被污染
        assertTrue(original.getContent().contains(SK));
    }

    @Test
    void unmaskUnknownPlaceholderShouldFailClosed() {
        assertThrows(MaskingException.class,
                () -> masker.unmask(ph("API_KEY_SECRET", "a1b2c3d4e5f60718"), new MaskMap()));
    }

    // ------------------------------------------------------------------

    private void assertMaskedAndRestorable(String text, String label) {
        MaskMap dictionary = new MaskMap();
        String masked = masker.mask(text, dictionary);
        assertTrue(masked.contains(open(label)), "expected label " + label + " in: " + masked);
        assertEquals(text, masker.unmask(masked, dictionary));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(sub, index)) >= 0) {
            count++;
            index += sub.length();
        }
        return count;
    }
}
