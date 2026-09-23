package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 问答引用来源（随 AI SDK metadata part 下发，前端 chip 展示，纯展示不可点击）
 * <p>
 * label 取值优先级：basename(source) → title → "未命名文档"；title 用作原生 tooltip。
 */
@Schema(description = "RAG 问答引用来源")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbSourceItem {

    @Schema(description = "来源文档 id")
    private String documentId;

    @Schema(description = "展示标签（文件名）", example = "记账规则配置说明.md")
    private String label;

    @Schema(description = "文档标题（tooltip 用）", example = "记账规则配置说明", nullable = true)
    private String title;
}
