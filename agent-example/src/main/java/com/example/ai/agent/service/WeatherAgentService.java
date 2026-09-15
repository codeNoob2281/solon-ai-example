package com.example.ai.agent.service;

import com.alibaba.fastjson2.JSON;
import com.example.ai.agent.dto.WeatherInfo;
import com.example.ai.agent.dto.WeatherQueryInput;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 天气查询服务（SimpleAgent 工具调用示例）
 * <p>
 * 链路：查询 DTO 序列化为 JSON 用户提示词 → {@code SimpleAgent.prompt(...).call()}
 * → 模型自动调用本地 mock 天气工具（get_weather_by_city_and_date）获取数据
 * → 模型按指令约束输出 JSON → {@code SimpleResponse.toBean(WeatherInfo.class)} 解析。
 */
@Slf4j
@Service
public class WeatherAgentService {

    /**
     * 天气查询 SimpleAgent（@Resource 按名称匹配 Bean weatherAgent）
     */
    @Resource
    private SimpleAgent weatherAgent;

    /**
     * 查询指定城市/日期的天气（由 Agent 自动调用 mock 工具完成）
     *
     * @param input 天气查询输入（city + date）
     * @return 天气信息（weather / minTemperature / maxTemperature）
     */
    public WeatherInfo query(WeatherQueryInput input) {
        if (input == null || isBlank(input.getCity()) || isBlank(input.getDate())) {
            throw new IllegalArgumentException("城市(city)与日期(date)不能为空");
        }

        String userPrompt = buildUserPrompt(input);
        String rawContent = null;
        WeatherInfo info;
        try {
            SimpleResponse response = weatherAgent.prompt(userPrompt).call();
            rawContent = response.getContent();
            info = response.toBean(WeatherInfo.class);
        } catch (Throwable e) {
            throw new IllegalStateException("天气查询失败: " + e.getMessage(), e);
        }

        if (info == null || isBlank(info.getWeather())) {
            log.warn("[Weather] 模型输出无法解析为结构化结果: {}", rawContent);
            throw new IllegalStateException("模型未返回有效天气数据");
        }

        log.info("[Weather] city={}, date={}, result={}", input.getCity(), input.getDate(), info);
        return info;
    }

    /**
     * 构建用户提示词：查询参数序列化为 JSON 传入（与需求定义的输入结构一致）
     */
    private String buildUserPrompt(WeatherQueryInput input) {
        return "请查询以下天气信息：\n" + JSON.toJSONString(input);
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
