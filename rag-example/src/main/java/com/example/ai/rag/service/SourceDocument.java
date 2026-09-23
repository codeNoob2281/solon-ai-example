package com.example.ai.rag.service;

import lombok.Getter;

/**
 * 入库源文档（「一个文件 / 一次粘贴 / 一个 URL」与入库记录一对一的中间概念）
 * <p>
 * 入库流水线的最顶层输入：文件字节 / 粘贴内容 / URL 抓取结果装配为本对象后，
 * 再经「章节切分（{@code sectionize}）→ 父块 token 切分 → MySQL 落库 → 子块向量化存 Milvus」
 * 完成入库。旧实现把 {@code MarkdownLoader} 的每个章节输出当作独立文档落库，
 * 导致一个 md 文件产生多条 {@code kb_document} 行；引入本类后「文件 → 记录」恢复一对一。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@code documentId}：文档 id；为空时由流水线生成（重建索引场景传入既有 id 复用）。</li>
 *   <li>{@code rawText}：原始全文（原样落 {@code kb_document.content}）。</li>
 *   <li>{@code title}：已按规则（§5.2：用户填写 → 文档内一级标题 → 文件名/兜底）解析好的标题。</li>
 *   <li>{@code source}：来源（服务器文件绝对路径 / 上传文件原始文件名 / URL；粘贴为 null）。</li>
 *   <li>{@code filename}：文件名（仅文件来源有，写入子块 metadata 供检索展示）。</li>
 *   <li>{@code contentType}：来源类型 text / file / url（既有 {@code kb_document.content_type} 语义）。</li>
 *   <li>{@code docType}：文档格式 markdown / html / text，决定 {@code sectionize} 行为与重建索引切分方式。</li>
 * </ul>
 */
@Getter
public class SourceDocument {

    /** 文档格式：Markdown（按标题章节切分） */
    public static final String DOC_TYPE_MARKDOWN = "markdown";
    /** 文档格式：纯文本（单章节，无子标题路径） */
    public static final String DOC_TYPE_TEXT = "text";
    /** 文档格式：HTML（HtmlSimpleLoader 转纯文本后单章节，无子标题路径） */
    public static final String DOC_TYPE_HTML = "html";

    private final String documentId;
    private final String rawText;
    private final String title;
    private final String source;
    private final String filename;
    private final String contentType;
    private final String docType;

    /**
     * @param documentId  文档 id（可空：为空时流水线生成；重建索引时传既有 id）
     * @param rawText     原始全文
     * @param title       已解析的文档标题（可空）
     * @param source      来源：文件绝对路径 / 上传原始文件名 / URL（粘贴为 null）
     * @param filename    文件名（仅文件来源；写子块 metadata 用，可空）
     * @param contentType 来源类型：text / file / url
     * @param docType     文档格式：markdown / html / text
     */
    public SourceDocument(String documentId, String rawText, String title,
                          String source, String filename, String contentType, String docType) {
        this.documentId = documentId;
        this.rawText = rawText;
        this.title = title;
        this.source = source;
        this.filename = filename;
        this.contentType = contentType;
        this.docType = docType;
    }
}
