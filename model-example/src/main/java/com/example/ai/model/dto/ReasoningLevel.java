package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推理档位（模型的可选思考强度档位）
 * <p>
 * {@code key} 为前端稳定标识（off/low/medium/high 或自定义），
 * {@code label} 为展示名，{@code apiValue} 为实际下发给供应商的 API 参数值：
 * 空或 {@code off} 表示关闭思考（thinking=false），其余作为 reasoning_effort 的值。
 */
@Schema(description = "推理档位")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningLevel {

    @Schema(description = "档位标识（前端稳定 key）", example = "high")
    private String key;

    @Schema(description = "展示名称", example = "高")
    private String label;

    @Schema(description = "下发的 API 参数值（空 / off = 关闭思考，其余为 reasoning_effort 取值）", example = "high")
    private String apiValue;
}
