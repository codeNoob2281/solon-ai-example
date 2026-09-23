package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档块列表响应（按 chunk_index 升序全量返回）
 */
@Schema(description = "文档块列表响应（预览）")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbChunkListResponse {

    @Schema(description = "文档 id")
    private String documentId;

    @Schema(description = "父块列表（按 chunk_index 升序，全量返回）")
    private List<KbChunkItem> chunks;
}
