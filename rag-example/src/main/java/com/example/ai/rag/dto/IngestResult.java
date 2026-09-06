package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入库结果（父子分段）
 */
@Schema(description = "入库结果（父子分段）")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestResult {

    /** 加载的文档数（分块前） */
    @Schema(description = "加载的文档数（分块前）", example = "3")
    private int sourceCount;

    /** 父块数（存 MySQL 分块表 kb_chunk） */
    @Schema(description = "父块数（存 MySQL 分块表 kb_chunk）", example = "5")
    private int parentChunkCount;

    /** 子块数（向量化后存 Milvus，独立字段 parent_id 关联父块） */
    @Schema(description = "子块数（向量化后存 Milvus，独立字段 parent_id 关联父块）", example = "23")
    private int childChunkCount;

    /** 耗时（毫秒） */
    @Schema(description = "耗时（毫秒）", example = "2380")
    private long elapsedMs;
}
