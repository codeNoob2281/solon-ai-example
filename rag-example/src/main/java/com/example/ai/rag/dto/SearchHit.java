package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索命中结果（父子分段：子块命中 → 映射父块）
 */
@Schema(description = "检索命中结果（父子分段：子块命中映射后的父块）")
@Data
@NoArgsConstructor
public class SearchHit {

    /** 父块 id（MySQL 分块表 kb_chunk.id） */
    @Schema(description = "父块 id（MySQL 分块表 kb_chunk.id）")
    private String id;

    /** 所属文档 id（kb_document.id） */
    @Schema(description = "所属文档 id（kb_document.id）")
    private String documentId;

    /** 相似度得分：该父块下命中子块的最高分（越高越相似） */
    @Schema(description = "相似度得分（该父块下命中子块的最高分，越高越相似）", example = "0.87")
    private double score;

    /** 父块内容（进入 LLM 上下文的完整段落） */
    @Schema(description = "父块内容（进入 LLM 上下文的完整段落）")
    private String content;

    /** 命中子块元数据（document_id / parent_id / source / filename / title / child_index 等） */
    @Schema(description = "命中子块元数据（document_id/parent_id/source/filename/title/child_index 等）")
    private Map<String, Object> metadata;

    public SearchHit(String id, String documentId, double score, String content, Map<String, Object> metadata) {
        this.id = id;
        this.documentId = documentId;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }
}
