package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库文档块列表项（文档块预览弹窗）
 */
@Schema(description = "知识库文档块列表项（父块）")
@Data
public class KbChunkItem {

    @Schema(description = "父块 id（同时为 Milvus 子块的 parent_id）")
    private String id;

    @Schema(description = "父块在文档内的序号（0 起连续）", example = "0")
    private Integer chunkIndex;

    @Schema(description = "子标题路径（如：配置提供步骤(面向产品) / 5.提供记账规则配置；txt/html 为 null）", nullable = true)
    private String title;

    @Schema(description = "父块字符数", example = "190")
    private Integer charCount;

    @Schema(description = "子块数量（存 Milvus）", example = "2")
    private Integer childCount;

    @Schema(description = "父块内容（含标题前缀全文，预览卡片正文）")
    private String content;
}
