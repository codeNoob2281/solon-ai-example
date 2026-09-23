package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库文档分页结果
 */
@Schema(description = "知识库文档分页结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbDocumentPage {

    @Schema(description = "总记录数", example = "87")
    private long total;

    @Schema(description = "当前页码（1 起）", example = "1")
    private int pageNum;

    @Schema(description = "每页条数（10/20/50/100）", example = "10")
    private int pageSize;

    @Schema(description = "总页数", example = "9")
    private long pages;

    @Schema(description = "当前页数据（按 updated_at DESC, id DESC 排序）")
    private List<KbDocumentItem> records;
}
