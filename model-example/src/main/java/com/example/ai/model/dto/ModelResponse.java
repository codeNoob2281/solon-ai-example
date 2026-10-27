package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型配置响应
 */
@Schema(description = "模型配置响应")
@Data
public class ModelResponse {

    @Schema(description = "模型 id", example = "12")
    private Long id;

    @Schema(description = "所属供应商 id", example = "1")
    private Long providerId;

    @Schema(description = "所属供应商名称", example = "我的 OpenAI")
    private String providerName;

    @Schema(description = "模型名称", example = "gpt-4o")
    private String modelName;

    @Schema(description = "显示名称", example = "GPT-4o")
    private String displayName;

    @Schema(description = "上下文窗口 tokens（0 = 未知）", example = "128000")
    private Integer contextWindow;

    @Schema(description = "最大输出 tokens（0 = 未知）", example = "16384")
    private Integer maxOutputTokens;

    @Schema(description = "输入类型：text / image", example = "[\"text\",\"image\"]")
    private List<String> inputTypes;

    @Schema(description = "预设推理档位")
    private List<ReasoningLevel> reasoningLevels;

    @Schema(description = "状态：1 启用 / 0 禁用", example = "1")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
