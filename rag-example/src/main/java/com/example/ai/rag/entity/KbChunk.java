package com.example.ai.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库父块表（kb_chunk，父子分段模式中的"分块"层）
 * <p>
 * 存储原始文档切分后的父块内容：父块由文档按较大粒度（默认 1000 token）切出，
 * 作为检索命中后进入 LLM 上下文的完整段落；每个父块再按较小粒度（默认 200 token）
 * 切出子块存入 Milvus（子块以独立字段 parent_id 关联本表 id）。
 * <p>
 * 子块不落 MySQL：子块只服务于向量检索（Milvus），父块内容即其完整上下文。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    /** 区块唯一标识（父块 UUID hex，服务侧生成；同时作为 Milvus 子块的 parent_id） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属文档 id（kb_document.id） */
    private String documentId;

    /** 父块在文档内的序号（0 开始，保持切分顺序） */
    private Integer chunkIndex;

    /** 父块内容（检索命中后进入 LLM 上下文） */
    private String content;

    /** 父块字符数 */
    private Integer charCount;

    /** 子块数量（存 Milvus） */
    private Integer childCount;

    private LocalDateTime createdAt;
}
