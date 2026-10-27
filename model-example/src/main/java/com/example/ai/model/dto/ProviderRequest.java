package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 模型供应商保存请求（新增 / 编辑）
 */
@Schema(description = "模型供应商保存请求")
@Data
public class ProviderRequest {

    @Schema(description = "供应商名称（唯一）", example = "我的 OpenAI", required = true)
    private String name;

    @Schema(description = "供应商类型（决定默认 Base URL 提示与调用方言）",
            example = "openai",
            allowableValues = {"openai", "deepseek", "qwen", "moonshot", "zhipu", "ollama", "custom"})
    private String type;

    @Schema(description = "接口地址（OpenAI 兼容 Base URL，不带 /chat/completions）",
            example = "https://api.openai.com/v1", required = true)
    private String baseUrl;

    @Schema(description = "API Key（编辑时留空表示不修改）", example = "sk-...")
    private String apiKey;

    @Schema(description = "状态：1 启用 / 0 禁用（缺省视为启用）", example = "1")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "模型列表（可选，随供应商一并保存：null=不修改，[]=清空）")
    private List<ModelRequest> models;
}
