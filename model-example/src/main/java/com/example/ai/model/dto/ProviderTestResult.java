package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商连通性测试结果（仅测「模型供应商」可达性：GET {baseUrl}/models）
 */
@Schema(description = "供应商连通性测试结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderTestResult {

    @Schema(description = "是否连通", example = "true")
    private boolean success;

    @Schema(description = "结果说明（成功为模型数量提示，失败为 HTTP 状态 / 异常摘要）",
            example = "连通正常，共 42 个模型")
    private String message;

    @Schema(description = "耗时（毫秒）", example = "320")
    private long elapsedMs;

    @Schema(description = "供应商返回的模型数量（失败为 0）", example = "42")
    private int modelCount;
}
