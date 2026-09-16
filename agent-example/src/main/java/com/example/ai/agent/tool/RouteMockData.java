package com.example.ai.agent.tool;

import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 两城间路线概况本地 Mock 数据源（不调用真实地图/铁路 API）
 * <p>
 * 以 {@code departureCity#targetCity} 的哈希做确定性伪随机：同一对城市结果稳定，
 * 不同城市对之间有区分度。返回公路距离、自驾时长、过路费，
 * 以及是否有直达高铁 / 直飞航班（供模型判断火车票 / 机票工具的查询价值）。
 */
public final class RouteMockData {

    private RouteMockData() {
    }

    /**
     * 查询两城间路线概况（Mock 实现）
     *
     * @param departureCity 出发城市
     * @param targetCity    目标城市
     * @return 路线概况 JSON 字符串
     */
    public static String query(String departureCity, String targetCity) {
        String key = (departureCity == null ? "" : departureCity) + "#" + (targetCity == null ? "" : targetCity);
        int seed = Math.abs(Objects.hashCode(key));

        int distanceKm = 300 + seed % 1200;                       // 300 ~ 1499 km
        double drivingHours = Math.round(distanceKm / 80.0 * 10) / 10.0; // 按 80km/h 估算
        int tollCost = distanceKm * 45 / 100;                     // 约 0.45 元/km
        boolean hasDirectTrain = seed % 5 != 0;                   // 80% 有直达高铁
        boolean hasDirectFlight = seed % 4 != 0;                  // 75% 有直飞航班

        String note;
        if (hasDirectTrain && hasDirectFlight) {
            note = "两城间高铁与航班均可直达，可按时间与预算自由选择";
        } else if (hasDirectTrain) {
            note = "无直飞航班（需中转），推荐高铁直达";
        } else if (hasDirectFlight) {
            note = "无直达高铁（需中转），推荐乘机出行";
        } else {
            note = "高铁与航班均需中转，请预留充足的途中时间";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("departureCity", departureCity);
        data.put("targetCity", targetCity);
        data.put("distanceKm", distanceKm);
        data.put("drivingDuration", drivingHours + "小时");
        data.put("tollCost", tollCost);
        data.put("hasDirectTrain", hasDirectTrain);
        data.put("hasDirectFlight", hasDirectFlight);
        data.put("note", note);
        return JSON.toJSONString(data);
    }
}
