package com.example.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 按草稿参数拉取远程模型列表的请求（新供应商尚未保存时使用）
 * <p>
 * {@code providerId} 非空时，{@code apiKey} 留空表示沿用已保存的 Key；
 * 两者都为空则请求不带鉴权头（本地 Ollama 等）。
 */
@Schema(description = "按草稿参数拉取远程模型列表")
@Data
public class RemoteModelQueryRequest {

    @Schema(description = "已保存供应商 id（可选；用于补全留空的 apiKey）", example = "1")
    private Long providerId;

    @Schema(description = "供应商类型（决定元信息补全的目录优先级）", example = "openai")
    private String type;

    @Schema(description = "接口地址（OpenAI 兼容 Base URL）", example = "https://api.openai.com/v1")
    private String baseUrl;

    @Schema(description = "API Key（留空时按 providerId 取已保存值）", example = "sk-...")
    private String apiKey;
}
