package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索命中结果
 */
@Schema(description = "检索命中结果")
@Data
@NoArgsConstructor
public class SearchHit {

    @Schema(description = "向量主键 id")
    private String id;

    @Schema(description = "相似度得分（越高越相似）", example = "0.87")
    private double score;

    @Schema(description = "命中的文本内容")
    private String content;

    @Schema(description = "文档元数据（如 title、source 等）")
    private Map<String, Object> metadata;

    public SearchHit(String id, double score, String content, Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }
}
