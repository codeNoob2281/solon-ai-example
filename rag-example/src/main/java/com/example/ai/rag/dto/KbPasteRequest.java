package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 粘贴内容入库请求体
 */
@Schema(description = "粘贴内容入库请求")
@Data
public class KbPasteRequest {

    @Schema(description = "标题（可选；缺省取文档内一级标题，再兜底\"未命名文档\"）", nullable = true)
    private String title;

    @Schema(description = "内容格式：markdown / text，默认 markdown", example = "markdown")
    private String format;

    @Schema(description = "内容（必填非空，≤ 2MB）")
    private String content;
}
