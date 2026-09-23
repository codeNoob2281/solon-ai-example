package com.example.ai.rag.loader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档内一级标题提取器（行级扫描简化实现，设计稿 §5.2）
 * <p>
 * 用于 {@code kb_document.title} 的解析：优先取文档内首个一级标题，取不到时由调用方
 * 回落文件名等兜底值。实现为行级扫描的简化版本：
 * <ul>
 *   <li>markdown：逐行扫描，跳过 ``` / ~~~ 围栏代码块内的行，匹配首个 ATX 一级标题
 *       （{@code ^#\s+\S}），返回去 {@code #} 后文本（含去掉行尾 ATX 闭合 {@code #}）；
 *       行内 HTML 等边界情况不追求完备（解析失败回落文件名，无破坏性）。</li>
 *   <li>html：正则取首个 {@code <h1>} 内文本，退而求其次取 {@code <title>}；
 *       标签内嵌套标签按"剥除标签 + 空白折叠"简化处理。</li>
 * </ul>
 */
public final class MarkdownTitleExtractor {

    /** 一级标题（ATX）："# " 开头且后接非空白内容 */
    private static final String FENCE_BACKTICK = "```";
    private static final String FENCE_TILDE = "~~~";

    private static final Pattern H1_PATTERN =
            Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 简化剥除 h1/title 内嵌套标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private MarkdownTitleExtractor() {
    }

    /**
     * 提取 Markdown 文档内首个一级标题（跳过围栏代码块内的行）
     *
     * @param markdown Markdown 原文
     * @return 一级标题文本；无则 null
     */
    public static String extractMarkdownTitle(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return null;
        }
        boolean inFence = false;
        String fenceMark = null;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.trim();
            if (inFence) {
                // 围栏闭合：以同一围栏标记开头的行（``` 或 ~~~，可带额外字符）
                if (fenceMark != null && trimmed.startsWith(fenceMark)) {
                    inFence = false;
                    fenceMark = null;
                }
                continue;
            }
            if (trimmed.startsWith(FENCE_BACKTICK)) {
                inFence = true;
                fenceMark = FENCE_BACKTICK;
                continue;
            }
            if (trimmed.startsWith(FENCE_TILDE)) {
                inFence = true;
                fenceMark = FENCE_TILDE;
                continue;
            }
            if (trimmed.startsWith("# ") && trimmed.length() > 2) {
                String title = trimAtxClosing(trimmed.substring(2).trim());
                return title.isEmpty() ? null : title;
            }
        }
        return null;
    }

    /**
     * 提取 HTML 文档标题：首个 {@code <h1>} 文本，退而求其次 {@code <title>}
     *
     * @param html HTML 原文
     * @return 标题文本；无则 null
     */
    public static String extractHtmlTitle(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        String title = firstGroupText(H1_PATTERN.matcher(html));
        if (title == null) {
            title = firstGroupText(TITLE_PATTERN.matcher(html));
        }
        return title;
    }

    /**
     * 去掉 ATX 标题行尾的闭合 # 序列（如 "# 标题 #" → "标题"）
     */
    private static String trimAtxClosing(String title) {
        String result = title;
        while (result.endsWith("#")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * 正则首个匹配的 group(1)：剥除内嵌标签、折叠空白后返回（空串归一为 null）
     */
    private static String firstGroupText(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        String text = matcher.group(1);
        if (text == null) {
            return null;
        }
        text = TAG_PATTERN.matcher(text).replaceAll(" ");
        text = text.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }
}
