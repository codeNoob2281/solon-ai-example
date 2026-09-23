package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档删除结果
 */
@Schema(description = "文档删除结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbDeleteResult {

    @Schema(description = "是否删除成功")
    private boolean deleted;
}
