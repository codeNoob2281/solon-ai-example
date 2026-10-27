package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单个模型的可用性测试结果
 * <p>
 * 测试方式：用固定提示词（默认「请回复pong」）发起一次最小化同步对话，
 * 校验「HTTP 通 + 拿到非空回复」——即模型可被该供应商正常调用。
 */
@Schema(description = "模型可用性测试结果")
@Data
public class ModelTestResult {

    @Schema(description = "模型 id（mc_model.id）", example = "12")
    private Long modelId;

    @Schema(description = "模型名称", example = "gpt-4o")
    private String modelName;

    @Schema(description = "显示名称", example = "GPT-4o")
    private String displayName;

    @Schema(description = "是否可用", example = "true")
    private boolean success;

    @Schema(description = "结果说明（成功为回复摘要，失败为异常摘要）", example = "可用，回复：pong")
    private String message;

    @Schema(description = "耗时（毫秒）", example = "820")
    private long elapsedMs;

    @Schema(description = "模型原始回复（截断；失败为 null）", example = "pong", nullable = true)
    private String reply;
}
