package com.example.ai.rag.entity;

import lombok.Data;

/**
 * 父块全文检索命中行（MySQL FULLTEXT 查询投影，非表实体）
 * <p>
 * 由 {@code KbChunkMapper.selectByFullText} 的查询结果映射：
 * kb_chunk 父块内容 + JOIN kb_document 的标题/来源 + 全文相关度分值，
 * 供关键词检索策略 {@code MysqlFullTextRetrievalStrategy} 组装 {@code SearchHit}。
 */
@Data
public class KbChunkFullTextRow {

    /** 父块 id（kb_chunk.id） */
    private String id;

    /** 所属文档 id（kb_document.id） */
    private String documentId;

    /** 父块内容 */
    private String content;

    /** 全文相关度分值（MySQL MATCH...AGAINST，SQL 内 ROUND 保留 4 位小数） */
    private double score;

    /** 所属文档标题（kb_document.title，可空） */
    private String title;

    /** 所属文档来源（kb_document.source，可空） */
    private String source;
}
