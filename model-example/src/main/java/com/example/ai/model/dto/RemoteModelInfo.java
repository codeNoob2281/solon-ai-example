package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 供应商远程模型信息（GET {baseUrl}/models 的解析结果 + 元信息补全）
 * <p>
 * {@code contextWindow} / {@code maxOutputTokens} / {@code inputTypes} 允许为空：
 * 先取供应商返回字段，缺失时由 models.dev 目录回落补全；仍缺失则为 null（前端留空，保存时按需手填）。
 */
@Schema(description = "供应商远程模型信息")
@Data
public class RemoteModelInfo {

    @Schema(description = "模型 ID（调用时使用的 model 名称）", example = "gpt-4o")
    private String id;

    @Schema(description = "模型归属方（部分供应商返回，如 openai / system）", example = "openai")
    private String ownedBy;

    @Schema(description = "上下文窗口 tokens（未知为 null）", example = "128000", nullable = true)
    private Integer contextWindow;

    @Schema(description = "最大输出 tokens（未知为 null）", example = "16384", nullable = true)
    private Integer maxOutputTokens;

    @Schema(description = "输入类型（未知为 null）", example = "[\"text\",\"image\"]", nullable = true)
    private List<String> inputTypes;
}
