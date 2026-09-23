package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文档变更结果（上传 / 粘贴 / 重建索引共用）
 */
@Schema(description = "知识库文档变更结果（上传/粘贴/重建索引共用）")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMutationResult {

    @Schema(description = "文档 id")
    private String documentId;

    @Schema(description = "父块数量（kb_chunk 行数）", example = "12")
    private int chunkCount;

    @Schema(description = "子块数量（存 Milvus）", example = "41")
    private int childChunkCount;

    @Schema(description = "耗时（毫秒）", example = "3210")
    private long elapsedMs;
}
