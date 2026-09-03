package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入库结果
 */
@Schema(description = "入库结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestResult {

    /** 加载的文档数（分块前） */
    @Schema(description = "加载的文档数（分块前）", example = "3")
    private int sourceCount;

    /** 分块后的块数（实际入库条数） */
    @Schema(description = "分块后的块数（实际入库条数）", example = "17")
    private int chunkCount;

    /** 耗时（毫秒） */
    @Schema(description = "耗时（毫秒）", example = "2380")
    private long elapsedMs;
}
