/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.ai.rag.loader;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.loader.AbstractOptionsDocumentLoader;
import org.noear.solon.core.util.SupplierEx;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Markdown 文档加载器（按标题层级做章节切分）
 *
 * <p>切分策略（上下文继承）：以标题级别构建章节树，只有拥有直接正文内容的标题才生成文档块；
 * 没有直接正文的父级标题（如 "##" 下直接跟 "###"）不再产生空块，而是作为路径前缀被下级块继承，
 * 避免向量检索召回无意义空块。块内容以完整标题路径为前缀，如：
 * <pre>"### 总纲 / 背景介绍 / 原因分析\n这里是原因的具体描述。"</pre>
 *
 * <p>规则：
 * <ul>
 *     <li>标题下有直接正文：标题成块（含正文），子标题继承其路径另成块，正文不重复；</li>
 *     <li>标题下无直接正文（只有子标题）：跳过，不生成块，仅作为路径传递；</li>
 *     <li>多级继承：## -&gt; ### -&gt; #### 只有 #### 有正文时，#### 块继承完整路径前缀；</li>
 *     <li>空标题（无文本或仅特殊符号）：过滤，不进入路径，其下内容归属上一层；</li>
 *     <li>文档开头无标题：内容以默认根路径 "Root" 归块。</li>
 * </ul>
 *
 * @author noear
 * @since 3.1
 */
public class MarkdownLoader extends AbstractOptionsDocumentLoader<MarkdownLoader.Options, MarkdownLoader> {
    /** 文档开头无标题时的默认根路径标题 */
    static final String DEFAULT_ROOT_TITLE = "Root";
    /** 路径段之间的分隔符 */
    static final String TITLE_PATH_SEPARATOR = " / ";

    private final SupplierEx<InputStream> source;
    private final Parser parser;

    public MarkdownLoader(byte[] source) {
        this(() -> new ByteArrayInputStream(source));
    }

    public MarkdownLoader(File source) {
        this(() -> Files.newInputStream(source.toPath()));
    }

    public MarkdownLoader(URL source) {
        this(source::openStream);
    }

    public MarkdownLoader(SupplierEx<InputStream> source) {
        if (source == null) {
            throw new IllegalArgumentException("Source cannot be null");
        }

        this.source = source;
        this.parser = Parser.builder().build();
        this.options = new Options();
        this.additionalMetadata.put("type", "markdown");
    }

    @Override
    public List<Document> load() throws IOException {
        try (InputStream input = this.source.get()) {
            // 显式 UTF-8 读取：避免依赖平台默认字符集（Windows 中文环境为 GBK，读 UTF-8 文档会乱码）
            Node md = this.parser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            return this.splitByHeadingHierarchy(this.collectTopTokens(md));
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /////////////////////
    // 第一遍：收集顶层块序列（commonmark 中标题与段落均为根节点的平级子块，
    // 标题层级树需按级别自行组织）

    private List<SectionToken> collectTopTokens(Node root) {
        List<SectionToken> tokens = new ArrayList<>();

        for (Node child = root.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading) {
                Heading heading = (Heading) child;
                tokens.add(new HeadingToken(heading.getLevel(), extractInlineText(heading)));
            } else if (child instanceof FencedCodeBlock) {
                String literal = ((FencedCodeBlock) child).getLiteral();
                if (Utils.isNotBlank(literal)) {
                    tokens.add(new ContentToken(literal, this.options.codeBlockAsNew));
                }
            } else if (child instanceof IndentedCodeBlock) {
                String literal = ((IndentedCodeBlock) child).getLiteral();
                if (Utils.isNotBlank(literal)) {
                    tokens.add(new ContentToken(literal, this.options.codeBlockAsNew));
                }
            } else if (child instanceof BlockQuote) {
                String text = extractBlocksText(child);
                if (Utils.isNotBlank(text)) {
                    tokens.add(new ContentToken(text, this.options.blockquoteAsNew));
                }
            } else if (child instanceof ListBlock) {
                String text = extractListText((ListBlock) child);
                if (Utils.isNotBlank(text)) {
                    tokens.add(new ContentToken(text, false));
                }
            } else if (child instanceof Paragraph) {
                String text = extractInlineText(child);
                if (Utils.isNotBlank(text)) {
                    tokens.add(new ContentToken(text, false));
                }
            } else if (child instanceof ThematicBreak) {
                if (this.options.horizontalLineAsNew) {
                    // 水平线只作为分块边界，自身不产生内容
                    tokens.add(new ContentToken("", true));
                }
            }
            // HtmlBlock 等其余块类型不参与章节切分
        }

        return tokens;
    }

    /**
     * 提取内联节点（段落/标题）的纯文本
     */
    private String extractInlineText(Node parent) {
        StringBuilder sb = new StringBuilder();
        appendInlineText(parent, sb);
        return sb.toString().trim();
    }

    private void appendInlineText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text) {
                sb.append(((Text) child).getLiteral());
            } else if (child instanceof Code) {
                sb.append(((Code) child).getLiteral());
            } else if (child instanceof Link) {
                Link link = (Link) child;
                String destination = link.getDestination();
                if (destination != null) {
                    StringBuilder desc = new StringBuilder();
                    appendInlineText(link, desc);
                    sb.append(" [").append(desc).append("](").append(destination).append(") ");
                }
            } else if (child instanceof Image) {
                Image image = (Image) child;
                String destination = image.getDestination();
                if (destination != null) {
                    sb.append(" ![").append(Optional.ofNullable(image.getTitle()).orElse(""))
                            .append("](").append(destination).append(") ");
                }
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
            } else {
                // Emphasis/StrongEmphasis 等容器型内联节点：递归提取内部文本
                appendInlineText(child, sb);
            }
        }
    }

    /**
     * 提取容器块（引用/列表项）内部的聚合文本
     */
    private String extractBlocksText(Node container) {
        StringBuilder sb = new StringBuilder();
        appendBlocksText(container, sb);
        return sb.toString().trim();
    }

    private void appendBlocksText(Node container, StringBuilder sb) {
        for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
            String text = null;
            if (child instanceof Paragraph || child instanceof Heading) {
                text = extractInlineText(child);
            } else if (child instanceof ListBlock) {
                text = extractListText((ListBlock) child);
            } else if (child instanceof BlockQuote) {
                text = extractBlocksText(child);
            } else if (child instanceof FencedCodeBlock) {
                text = ((FencedCodeBlock) child).getLiteral();
            } else if (child instanceof IndentedCodeBlock) {
                text = ((IndentedCodeBlock) child).getLiteral();
            }

            if (Utils.isNotBlank(text)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
    }

    private String extractListText(ListBlock list) {
        StringBuilder sb = new StringBuilder();
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String text = extractBlocksText(item);
            if (Utils.isNotBlank(text)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /////////////////////
    // 第二遍：按标题层级切分（上下文继承）

    private List<Document> splitByHeadingHierarchy(List<SectionToken> tokens) {
        List<Document> documents = new ArrayList<>();
        this.emitRange(tokens, 0, tokens.size(), new ArrayList<>(), documents);
        return documents;
    }

    /**
     * 处理 [from, to) 区间：区间内的正文直挂 path 路径，非空标题向下开启子区间并继承路径。
     * 只有累积到直接正文的层级才生成块，空父级标题不产生空块。
     */
    private void emitRange(List<SectionToken> tokens, int from, int to, List<PathSeg> path, List<Document> out) {
        StringBuilder direct = new StringBuilder();
        int i = from;

        while (i < to) {
            SectionToken token = tokens.get(i);

            if (token instanceof ContentToken) {
                ContentToken content = (ContentToken) token;
                if (content.standalone) {
                    // 代码块/引用独立成块（继承当前路径），先封存已积累正文
                    this.flushDirect(path, direct, out);
                    if (Utils.isNotEmpty(content.text)) {
                        out.add(this.buildSectionDocument(path, content.text));
                    }
                } else {
                    if (direct.length() > 0) {
                        direct.append(' ');
                    }
                    direct.append(content.text);
                }
                i++;
            } else if (((HeadingToken) token).blank) {
                // 空标题过滤：不进路径，其下内容继续归属当前层级
                i++;
            } else {
                // 子标题前封存当前层已积累正文（父级内容先于子级输出）
                this.flushDirect(path, direct, out);

                HeadingToken heading = (HeadingToken) token;
                int end = this.sectionEnd(tokens, i + 1, to, heading.level);
                List<PathSeg> childPath = new ArrayList<>(path);
                childPath.add(new PathSeg(heading.title, heading.level));
                this.emitRange(tokens, i + 1, end, childPath, out);
                i = end;
            }
        }

        this.flushDirect(path, direct, out);
    }

    /**
     * 标题辖区终点：下一个级别不高于该标题的非空标题位置
     */
    private int sectionEnd(List<SectionToken> tokens, int from, int to, int level) {
        int j = from;
        while (j < to) {
            SectionToken token = tokens.get(j);
            if (token instanceof HeadingToken) {
                HeadingToken heading = (HeadingToken) token;
                if (!heading.blank && heading.level <= level) {
                    break;
                }
            }
            j++;
        }
        return j;
    }

    /**
     * 封存当前层级积累的正文为块（无正文则跳过，即消除空块）
     */
    private void flushDirect(List<PathSeg> path, StringBuilder direct, List<Document> out) {
        if (direct.length() == 0) {
            return;
        }

        String body = direct.toString();
        direct.setLength(0);
        out.add(this.buildSectionDocument(path, body));
    }

    /**
     * 由标题路径 + 正文构建文档块；路径为空时回落默认根路径 "Root"。
     * 内容格式："{#×级别} {标题路径}\n{正文}"
     */
    private Document buildSectionDocument(List<PathSeg> path, String body) {
        List<PathSeg> effectivePath = path.isEmpty()
                ? Collections.singletonList(new PathSeg(DEFAULT_ROOT_TITLE, 1))
                : path;
        PathSeg current = effectivePath.get(effectivePath.size() - 1);

        StringBuilder content = new StringBuilder();
        for (int i = 0; i < current.level; i++) {
            content.append('#');
        }
        content.append(' ');
        for (int i = 0; i < effectivePath.size(); i++) {
            if (i > 0) {
                content.append(TITLE_PATH_SEPARATOR);
            }
            content.append(effectivePath.get(i).title);
        }
        content.append('\n').append(body);

        Document document = new Document();
        document.content(content.toString());
        document.metadata(this.additionalMetadata);
        document.metadata("category", "header_" + current.level);
        document.metadata("title", current.title);
        document.metadata("title_path", joinTitles(effectivePath));
        return document;
    }

    private static String joinTitles(List<PathSeg> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(TITLE_PATH_SEPARATOR);
            }
            sb.append(path.get(i).title);
        }
        return sb.toString();
    }

    /////////////////////

    /**
     * 顶层块 token
     */
    abstract static class SectionToken {
    }

    /**
     * 顶层标题 token
     */
    static final class HeadingToken extends SectionToken {
        final int level;
        final String title;
        final boolean blank;

        HeadingToken(int level, String title) {
            this.level = level;
            this.title = title == null ? "" : title.trim();
            this.blank = !hasMeaningfulText(this.title);
        }

        /**
         * 标题文本须含字母或数字（中日韩等均算），否则视为空标题
         */
        private static boolean hasMeaningfulText(String title) {
            if (Utils.isBlank(title)) {
                return false;
            }

            for (int i = 0; i < title.length(); i++) {
                if (Character.isLetterOrDigit(title.charAt(i))) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * 顶层正文 token（段落/代码块/引用/列表等聚合文本）
     */
    static final class ContentToken extends SectionToken {
        final String text;
        /**
         * 是否强制独立成块（代码块/引用可选；水平线仅作分块边界）
         */
        final boolean standalone;

        ContentToken(String text, boolean standalone) {
            this.text = text == null ? "" : text;
            this.standalone = standalone;
        }
    }

    /**
     * 标题路径段
     */
    static final class PathSeg {
        final String title;
        final int level;

        PathSeg(String title, int level) {
            this.title = title;
            this.level = level;
        }
    }


    /// /////////////////////


    /**
     * 加载选项
     */
    public static class Options {
        /**
         * 有水平线新建（水平线处拆分，前后内容分成同一路径下的两个块）
         */
        private boolean horizontalLineAsNew;
        /**
         * 有代码块新建（代码块独立成块，继承当前标题路径）
         */
        private boolean codeBlockAsNew;
        /**
         * 有块引用新建（引用块独立成块，继承当前标题路径）
         */
        private boolean blockquoteAsNew;

        public Options horizontalLineAsNew(boolean horizontalLineAsNew) {
            this.horizontalLineAsNew = horizontalLineAsNew;
            return this;
        }

        public Options codeBlockAsNew(boolean codeBlockAsNew) {
            this.codeBlockAsNew = codeBlockAsNew;
            return this;
        }

        public Options blockquoteAsNew(boolean blockquoteAsNew) {
            this.blockquoteAsNew = blockquoteAsNew;
            return this;
        }
    }
}
