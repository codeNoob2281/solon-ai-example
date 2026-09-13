package com.example.ai.agent.tool;

import org.noear.snack4.ONode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 天气查询本地 Mock 数据源（不调用真实天气 API）
 * <p>
 * 以 {@code city#date} 的哈希做确定性伪随机：同一城市同一日期结果稳定，
 * 不同城市/日期之间有区分度，便于演示工具调用与结构化回传。
 * 返回 JSON 字符串，包含 city / date / weather / minTemperature / maxTemperature。
 */
public final class WeatherMockData {

    /** 天气状况池 */
    private static final String[] WEATHERS = {"晴", "多云", "阴", "多云转晴", "小雨", "阵雨转多云", "雾"};

    private WeatherMockData() {
    }

    /**
     * 查询天气（Mock 实现）
     *
     * @param city 城市名称
     * @param date 查询日期（yyyy-MM-dd）
     * @return 天气 JSON 字符串
     */
    public static String query(String city, String date) {
        String key = (city == null ? "" : city) + "#" + (date == null ? "" : date);
        int seed = Math.abs(Objects.hashCode(key));

        String weather = WEATHERS[seed % WEATHERS.length];
        int minTemperature = 5 + seed % 18;                  // 5 ~ 22℃
        int maxTemperature = minTemperature + 5 + seed % 9;  // min+5 ~ min+13℃

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("city", city);
        data.put("date", date);
        data.put("weather", weather);
        data.put("minTemperature", minTemperature);
        data.put("maxTemperature", maxTemperature);
        return ONode.serialize(data);
    }
}
