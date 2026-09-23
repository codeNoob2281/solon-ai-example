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
 * 一个文件 / 一次粘贴 / 一个 URL = 一行记录；文档经「章节切分 + 父块 token 切分」
 * 产生 1..N 个父块（kb_chunk），父块再切子块存入 Milvus。
 * <p>
 * 说明：{@code content} 保存原始全文（重建索引的切分依据；父块行另持切分后内容），
 * {@code docType} 为文档格式（markdown/html/text），决定重建索引时的 sectionize 行为。
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

    /** 文档格式：markdown / html / text（文件按后缀映射，粘贴按所选格式） */
    private String docType;

    /** 原始全文（重建索引依据；粘贴入库也存此列） */
    private String content;

    /** 原始内容字符数 */
    private Integer contentChars;

    /** 父块数量 */
    private Integer chunkCount;

    /** 入库状态：INGESTING（写库中）/ INGESTED（Milvus 写入完成）/ FAILED（写入失败） */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
