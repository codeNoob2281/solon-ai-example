package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 天气查询结果（SimpleAgent 调用本地 mock 天气工具后生成的结构化输出）
 */
@Data
@Schema(description = "天气查询结果（天气状况 + 最低/最高气温）")
public class WeatherInfo {

    @Schema(description = "天气状况", example = "多云")
    private String weather;

    @Schema(description = "最低气温（℃，整数）", example = "18")
    private Integer minTemperature;

    @Schema(description = "最高气温（℃，整数）", example = "26")
    private Integer maxTemperature;
}
