package com.example.ai.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档表（kb_document）
 * <p>
 * 存储知识库的文档信息（父子分段模式中的"文档"层）：
 * 一个文档经父块切分后产生 1..N 个父块（kb_chunk），父块再切子块存入 Milvus。
 * <p>
 * 说明：不存文档全文——父块行已持有切分后的全部内容，避免重复存储；
 * 如需重切可从源文件重新入库。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 文档唯一标识（UUID hex，服务侧生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 标题（可选） */
    private String title;

    /** 来源：文件绝对路径 / URL；原始文本入库为空 */
    private String source;

    /** 来源类型：text / file / url */
    private String contentType;

    /** 原始内容字符数 */
    private Integer contentChars;

    /** 父块数量 */
    private Integer chunkCount;

    /** 入库状态：INGESTING（写库中）/ INGESTED（Milvus 写入完成）/ FAILED（写入失败） */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
