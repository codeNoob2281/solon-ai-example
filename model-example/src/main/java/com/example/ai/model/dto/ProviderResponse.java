package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型供应商响应（含其下模型列表；列表页与编辑页共用）
 * <p>
 * API Key 不回显明文：只给 {@code hasApiKey} 标记与末四位提示 {@code apiKeyHint}。
 */
@Schema(description = "模型供应商响应")
@Data
public class ProviderResponse {

    @Schema(description = "供应商 id", example = "1")
    private Long id;

    @Schema(description = "供应商名称", example = "我的 OpenAI")
    private String name;

    @Schema(description = "供应商类型", example = "openai")
    private String type;

    @Schema(description = "接口地址", example = "https://api.openai.com/v1")
    private String baseUrl;

    @Schema(description = "是否已配置 API Key")
    private Boolean hasApiKey;

    @Schema(description = "API Key 末四位提示（未配置时为 null）", example = "…9f3a")
    private String apiKeyHint;

    @Schema(description = "状态：1 启用 / 0 禁用", example = "1")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "模型数量", example = "3")
    private Integer modelCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "模型列表")
    private List<ModelResponse> models;
}
