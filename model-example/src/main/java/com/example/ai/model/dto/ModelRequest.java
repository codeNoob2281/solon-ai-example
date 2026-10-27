package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 模型配置保存请求（随供应商一并保存）
 * <p>
 * 窗口字段为 <b>字符串</b>，支持简写（{@code 1M} / {@code 200K} / {@code 128000}），
 * 留空或 0 表示「自动获取」（供应商 /models 字段 → models.dev 目录）。
 * 响应侧统一返回解析后的整数（见 {@link ModelResponse}）。
 */
@Schema(description = "模型配置保存请求")
@Data
public class ModelRequest {

    @Schema(description = "模型 id（编辑已有模型时传入；为空表示新增）", example = "12")
    private Long id;

    @Schema(description = "模型名称（调用的 model 字段）", example = "gpt-4o", required = true)
    private String modelName;

    @Schema(description = "显示名称（为空时回落模型名称）", example = "GPT-4o")
    private String displayName;

    @Schema(description = "上下文窗口（支持 1M / 200K 简写；留空自动获取）", example = "128K")
    private String contextWindow;

    @Schema(description = "最大输出 tokens（支持 256K 简写；留空自动获取）", example = "16K")
    private String maxOutputTokens;

    @Schema(description = "输入类型（多选：text / image；空视为 text）", example = "[\"text\",\"image\"]")
    private List<String> inputTypes;

    @Schema(description = "预设推理档位（留空表示仅「默认」一档）")
    private List<ReasoningLevel> reasoningLevels;

    @Schema(description = "状态：1 启用 / 0 禁用（缺省视为启用）", example = "1")
    private Integer status;
}
