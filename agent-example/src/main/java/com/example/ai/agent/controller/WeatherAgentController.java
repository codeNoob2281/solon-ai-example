package com.example.ai.agent.controller;

import com.example.ai.agent.dto.WeatherInfo;
import com.example.ai.agent.dto.WeatherQueryInput;
import com.example.ai.agent.service.WeatherAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 天气查询接口（SimpleAgent 工具调用示例）
 * <pre>
 * POST /agent/weather/query   天气查询（Agent 自动调用本地 mock 天气工具）
 * </pre>
 */
@Slf4j
@Tag(name = "天气查询", description = "基于 solon-ai-agent SimpleAgent 的工具调用示例："
        + "输入城市与日期，Agent 自动调用本地 mock 天气工具（get_weather_by_city_and_date）获取数据，"
        + "输出天气状况 + 最低/最高气温")
@RestController
@RequestMapping("/agent/weather")
public class WeatherAgentController {

    private final WeatherAgentService weatherAgentService;

    public WeatherAgentController(WeatherAgentService weatherAgentService) {
        this.weatherAgentService = weatherAgentService;
    }

    /**
     * 天气查询：POST /agent/weather/query
     * <pre>{@code
     * 请求：{ "city": "北京", "date": "2026-09-14" }
     * 响应：{ "weather": "多云", "minTemperature": 18, "maxTemperature": 26 }
     * }</pre>
     */
    @Operation(summary = "天气查询（SimpleAgent 工具调用）",
            description = "输入城市与日期，SimpleAgent 自动调用本地 mock 天气工具获取数据，"
                    + "返回天气状况（weather）与最低/最高气温（minTemperature/maxTemperature，℃）")
    @PostMapping("/query")
    public WeatherInfo query(@RequestBody WeatherQueryInput input) {
        log.info("[Weather] 收到查询请求: city={}, date={}", input == null ? null : input.getCity(),
                input == null ? null : input.getDate());
        return weatherAgentService.query(input);
    }
}
