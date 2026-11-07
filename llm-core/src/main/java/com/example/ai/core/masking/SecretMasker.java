package com.example.ai.core.masking;

import cn.hutool.core.util.StrUtil;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏引擎：明文 ↔ 掩码占位符的双向转换
 * <p>
 * 职责：
 * <ul>
 *   <li>{@link #mask(String, MaskMap)} 明文 → 掩码（确定性，基于明文摘要）；</li>
 *   <li>{@link #unmask(String, MaskMap)} 掩码 → 明文（映射缺失时 fail-closed）；</li>
 *   <li>{@link #unmaskArgs(Map, MaskMap)} 递归还原工具参数（嵌套 Map/List/字符串）；</li>
 *   <li>{@link #maskToolResult(ToolResult, MaskMap)} 掩码工具响应（含 ContentBlock）；</li>
 *   <li>{@link #maskPrompt(Prompt, MaskMap)} 兜底掩码请求消息（按角色重建新对象，不污染会话）。</li>
 * </ul>
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class SecretMasker {

    private final List<SecretRule> rules;

    /** 掩码占位符整体识别正则（仅匹配本引擎登记的标签） */
    private final Pattern placeholderPattern;

    public SecretMasker(MaskingProperties properties) {
        this.rules = buildRules(properties);
        List<String> labels = new ArrayList<>(rules.size());
        for (SecretRule rule : rules) {
            labels.add(rule.getLabel());
        }
        this.placeholderPattern = SensitiveMaskUtil.placeholderPattern(labels);
    }

    // ------------------------------------------------------------------
    // 对外能力

    /**
     * 掩码文本：命中任一规则的明文替换为占位符并登记映射
     */
    public String mask(String text, MaskMap map) {
        if (StrUtil.isEmpty(text)) {
            return text;
        }
        String result = text;
        for (SecretRule rule : rules) {
            Matcher matcher = rule.getPattern().matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            int group = rule.getValueGroup();
            while (matcher.find()) {
                String value = matcher.group(group);
                if (value == null) {
                    continue;
                }
                // 值过滤（如密码：排除占位符/常见非密码词）
                if (rule.getValueFilter() != null && !rule.getValueFilter().test(value)) {
                    continue;
                }
                // 只替换目标捕获组，保留键/上下文
                String masked = SensitiveMaskUtil.mask(rule.getLabel(), value, map);
                String whole = matcher.group();
                int start = matcher.start(group) - matcher.start();
                int end = matcher.end(group) - matcher.start();
                String replacement = whole.substring(0, start) + masked + whole.substring(end);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            if (changed) {
                matcher.appendTail(sb);
                result = sb.toString();
            }
        }
        return result;
    }

    /**
     * 还原文本：占位符替换回明文；任一占位符在映射表中缺失则 fail-closed 抛异常
     */
    public String unmask(String text, MaskMap map) {
        return SensitiveMaskUtil.unmask(text, map, placeholderPattern);
    }

    /**
     * 递归还原工具参数（就地写回 Map / List）
     */
    public void unmaskArgs(Map<String, Object> args, MaskMap map) {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            entry.setValue(unmaskValue(entry.getValue(), map));
        }
    }

    /**
     * 掩码工具响应（文本块掩码、非文本块原样保留；未发生变化时返回原对象）
     */
    public ToolResult maskToolResult(ToolResult result, MaskMap map) {
        if (result == null) {
            return null;
        }
        boolean changed = false;
        ToolResult masked = new ToolResult();
        for (ContentBlock block : result.getBlocks()) {
            if (block instanceof TextBlock) {
                TextBlock textBlock = (TextBlock) block;
                String content = textBlock.getContent();
                String maskedContent = mask(content, map);
                if (!Objects.equals(content, maskedContent)) {
                    changed = true;
                    masked.addBlock(TextBlock.of(maskedContent, textBlock.getMimeType()));
                } else {
                    masked.addBlock(block);
                }
            } else {
                masked.addBlock(block);
            }
        }
        if (!changed) {
            return result;
        }
        masked.setError(result.isError());
        if (result.metas() != null && !result.metas().isEmpty()) {
            masked.metas().putAll(result.metas());
        }
        return masked;
    }

    /**
     * 兜底掩码请求消息：按角色重建新对象后整体替换（不原地改，避免污染会话）
     */
    public void maskPrompt(Prompt prompt, MaskMap map) {
        if (prompt == null || prompt.isEmpty()) {
            return;
        }
        List<ChatMessage> masked = new ArrayList<>(prompt.getMessages().size());
        for (ChatMessage message : prompt.getMessages()) {
            masked.add(maskMessage(message, map));
        }
        prompt.replaceMessages(masked);
    }

    // ------------------------------------------------------------------
    // 内部实现

    private Object unmaskValue(Object value, MaskMap map) {
        if (value instanceof String) {
            return unmask((String) value, map);
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) value;
            for (Map.Entry<String, Object> entry : nested.entrySet()) {
                entry.setValue(unmaskValue(entry.getValue(), map));
            }
            return nested;
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                list.set(i, unmaskValue(list.get(i), map));
            }
            return list;
        }
        return value;
    }

    private ChatMessage maskMessage(ChatMessage message, MaskMap map) {
        if (message instanceof SystemMessage) {
            String content = message.getContent();
            String masked = mask(content, map);
            if (Objects.equals(content, masked)) {
                return message;
            }
            return copyMetadata(message, ChatMessage.ofSystem(masked));
        }

        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            String content = userMessage.getContent();
            String maskedContent = mask(content, map);
            List<ContentBlock> blocks = userMessage.getBlocks();
            List<ContentBlock> maskedBlocks = maskBlocks(blocks, map);
            if (Objects.equals(content, maskedContent) && blocks == maskedBlocks) {
                return message;
            }
            Contents contents = new Contents();
            if (maskedBlocks != null) {
                for (ContentBlock block : maskedBlocks) {
                    contents.addBlock(block);
                }
            }
            if (contents.getContent() == null && maskedContent != null) {
                contents.addText(maskedContent);
            }
            return copyMetadata(message, ChatMessage.ofUser(contents));
        }

        if (message instanceof AssistantMessage) {
            AssistantMessage assistant = (AssistantMessage) message;
            String text = assistant.getText();
            String thinking = assistant.getThinking();
            String maskedText = mask(text, map);
            String maskedThinking = mask(thinking, map);
            List<ContentBlock> blocks = assistant.getBlocks();
            List<ContentBlock> maskedBlocks = maskBlocks(blocks, map);
            if (Objects.equals(text, maskedText)
                    && Objects.equals(thinking, maskedThinking)
                    && blocks == maskedBlocks) {
                return message;
            }
            AssistantMessage rebuilt = new AssistantMessage(maskedText, maskedThinking,
                    assistant.getToolCalls(), maskedBlocks);
            return copyMetadata(message, rebuilt);
        }

        if (message instanceof ToolMessage) {
            ToolMessage toolMessage = (ToolMessage) message;
            ToolResult original = toToolResult(toolMessage);
            ToolResult masked = maskToolResult(original, map);
            if (masked == original) {
                return message;
            }
            return copyMetadata(message,
                    ChatMessage.ofTool(masked, toolMessage.getName(),
                            toolMessage.getToolCallId(), toolMessage.isReturnDirect()));
        }

        return message;
    }

    private List<ContentBlock> maskBlocks(List<ContentBlock> blocks, MaskMap map) {
        if (blocks == null) {
            return null;
        }
        boolean changed = false;
        List<ContentBlock> masked = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                TextBlock textBlock = (TextBlock) block;
                String content = textBlock.getContent();
                String maskedContent = mask(content, map);
                if (!Objects.equals(content, maskedContent)) {
                    changed = true;
                    masked.add(TextBlock.of(maskedContent, textBlock.getMimeType()));
                } else {
                    masked.add(block);
                }
            } else {
                masked.add(block);
            }
        }
        return changed ? masked : blocks;
    }

    private ToolResult toToolResult(ToolMessage toolMessage) {
        ToolResult result = new ToolResult();
        if (toolMessage.getBlocks() != null) {
            for (ContentBlock block : toolMessage.getBlocks()) {
                result.addBlock(block);
            }
        }
        if (result.getBlocks().isEmpty() && toolMessage.getContent() != null) {
            result.addText(toolMessage.getContent());
        }
        result.setError(toolMessage.isError());
        if (toolMessage.getMetadata() != null && !toolMessage.getMetadata().isEmpty()) {
            result.metas().putAll(toolMessage.getMetadata());
        }
        return result;
    }

    private ChatMessage copyMetadata(ChatMessage source, ChatMessage target) {
        if (source.getMetadata() != null && !source.getMetadata().isEmpty()) {
            target.addMetadata(source.getMetadata());
        }
        return target;
    }

    private static List<SecretRule> buildRules(MaskingProperties properties) {
        List<SecretRule> rules = new ArrayList<>();
        // 内置：各 DesensitizedType 中可自动识别的类型（含 sk- API Key；按枚举顺序，身份证先于银行卡）
        for (DesensitizedType type : DesensitizedType.values()) {
            if (StrUtil.isNotBlank(type.getRegex())) {
                rules.add(new SecretRule(type.name(), type.getRegex(),
                        type.getValueGroup(), type.getValueFilter()));
            }
        }
        // 配置规则：按标签覆盖内置规则，或追加新规则
        if (properties != null && properties.getRules() != null) {
            for (MaskingProperties.Rule rule : properties.getRules()) {
                if (rule == null || StrUtil.isBlank(rule.getLabel()) || StrUtil.isBlank(rule.getRegex())) {
                    continue;
                }
                replaceOrAdd(rules, new SecretRule(rule.getLabel(), rule.getRegex()));
            }
        }
        return rules;
    }

    /**
     * 按标签替换已有规则，不存在则追加
     */
    private static void replaceOrAdd(List<SecretRule> rules, SecretRule rule) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getLabel().equals(rule.getLabel())) {
                rules.set(i, rule);
                return;
            }
        }
        rules.add(rule);
    }
}
