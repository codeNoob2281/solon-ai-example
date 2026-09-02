package com.example.ai.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入库结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestResult {
    /** 加载的文档数（分块前） */
    private int sourceCount;
    /** 分块后的块数（实际入库条数） */
    private int chunkCount;
    /** 耗时（毫秒） */
    private long elapsedMs;
}
