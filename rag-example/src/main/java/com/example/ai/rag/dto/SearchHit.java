package com.example.ai.rag.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索命中结果
 */
@Data
@NoArgsConstructor
public class SearchHit {
    private String id;
    private double score;
    private String content;
    private Map<String, Object> metadata;

    public SearchHit(String id, double score, String content, Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }
}
