package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库汇总信息（知识库管理页顶部统计条）
 */
@Schema(description = "知识库汇总信息")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbSummary {

    @Schema(description = "文档数（kb_document 行数）", example = "12")
    private long documentCount;

    @Schema(description = "文档块数（kb_chunk 父块行数）", example = "345")
    private long chunkCount;

    @Schema(description = "文档总字符数（content_chars 之和）", example = "123456")
    private long totalChars;

    @Schema(description = "向量维度（Milvus 集合 schema 读取，回落嵌入模型；不可用时为 null）", example = "1024", nullable = true)
    private Integer vectorDimension;
}
