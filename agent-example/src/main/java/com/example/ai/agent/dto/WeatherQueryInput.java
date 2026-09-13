package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 天气查询输入（SimpleAgent 工具调用示例）
 * <pre>{@code
 * { "city": "北京", "date": "2026-09-14" }
 * }</pre>
 */
@Data
@Schema(description = "天气查询输入")
public class WeatherQueryInput {

    @Schema(description = "城市名称", example = "北京")
    private String city;

    @Schema(description = "查询日期（yyyy-MM-dd）", example = "2026-09-14")
    private String date;
}
