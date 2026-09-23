package com.example.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档列表项（分页列表行；不含原始全文 content）
 */
@Schema(description = "知识库文档列表项（不含原始全文）")
@Data
public class KbDocumentItem {

    @Schema(description = "文档 id")
    private String id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "来源（文件绝对路径 / 上传文件名 / URL；粘贴为 null）", nullable = true)
    private String source;

    @Schema(description = "来源类型：text/file/url", example = "file")
    private String contentType;

    @Schema(description = "文档格式：markdown/html/text", example = "markdown", nullable = true)
    private String docType;

    @Schema(description = "原始全文字符数", example = "8321")
    private Integer contentChars;

    @Schema(description = "父块数量", example = "12")
    private Integer chunkCount;

    @Schema(description = "入库状态：INGESTING/INGESTED/FAILED", example = "INGESTED")
    private String status;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
