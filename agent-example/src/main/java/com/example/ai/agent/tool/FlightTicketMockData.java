package com.example.ai.agent.tool;

import com.alibaba.fastjson2.JSON;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 机票本地 Mock 数据源（不调用真实航司/OTA API）
 * <p>
 * 以 {@code departureCity#targetCity#date} 的哈希做确定性伪随机：同一查询结果稳定。
 * 返回 2~3 个航班，含航司/航班号、出发/到达时刻、飞行时长、经济舱价格、折扣与余座；
 * 少量航班余座为 0，便于演示模型的筛选与兜底逻辑。
 */
public final class FlightTicketMockData {

    /** 固定起飞时刻池（覆盖早中晚） */
    private static final String[] DEPARTURE_SLOTS = {
            "07:30", "09:05", "11:20", "13:45", "15:50", "18:25", "21:00"
    };

    /** 航司池（与二字码一一对应） */
    private static final String[] AIRLINES = {"国航", "东航", "南航", "海航", "厦航", "春秋航空"};
    private static final String[] AIRLINE_CODES = {"CA", "MU", "CZ", "HU", "MF", "9C"};

    private FlightTicketMockData() {
    }

    /**
     * 查询机票（Mock 实现）
     *
     * @param departureCity 出发城市
     * @param targetCity    目标城市
     * @param date          乘机日期（yyyy-MM-dd）
     * @return 航班列表 JSON 字符串
     */
    public static String query(String departureCity, String targetCity, String date) {
        String key = (departureCity == null ? "" : departureCity) + "#"
                + (targetCity == null ? "" : targetCity) + "#"
                + (date == null ? "" : date);
        int seed = Math.abs(Objects.hashCode(key));

        int count = 2 + seed % 2; // 2 ~ 3 个航班
        List<Map<String, Object>> flights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int varSeed = seed + i * 211;
            flights.add(buildFlight(varSeed, (seed / 5 + i * 3) % DEPARTURE_SLOTS.length,
                    departureCity, targetCity));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("departureCity", departureCity);
        data.put("targetCity", targetCity);
        data.put("date", date);
        data.put("flights", flights);
        return JSON.toJSONString(data);
    }

    private static Map<String, Object> buildFlight(int varSeed, int slotIndex, String departureCity, String targetCity) {
        String departureTime = DEPARTURE_SLOTS[slotIndex];
        int durationMinutes = 110 + varSeed % 90; // 1 小时 50 分 ~ 3 小时 19 分

        LocalTime departure = LocalTime.parse(departureTime);
        LocalTime arrival = departure.plusMinutes(durationMinutes);
        boolean nextDay = arrival.isBefore(departure); // plusMinutes 跨天回绕即次日到达

        int airlineIndex = varSeed % AIRLINES.length;
        String flightNo = AIRLINE_CODES[airlineIndex] + (1000 + varSeed % 8000);
        double discount = 4.0 + (varSeed % 50) / 10.0;   // 4.0 ~ 8.9 折
        int economyPrice = 380 + varSeed % 920;          // 经济舱 380 ~ 1299 元
        int seatsLeft = (varSeed % 8 == 0) ? 0 : 3 + varSeed % 28; // 约 1/8 概率无座

        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("flightNo", flightNo);
        flight.put("airline", AIRLINES[airlineIndex]);
        flight.put("departureAirport", departureCity + "国际机场T2");
        flight.put("arrivalAirport", targetCity + "国际机场T1");
        flight.put("departureTime", departureTime);
        flight.put("arrivalTime", arrival.toString());
        flight.put("arrivalNote", nextDay ? "次日到达" : "当日到达");
        flight.put("duration", (durationMinutes / 60) + "小时" + (durationMinutes % 60) + "分");
        flight.put("economyPrice", economyPrice);
        flight.put("discount", discount);
        flight.put("seatsLeft", seatsLeft);
        flight.put("status", seatsLeft == 0 ? "无座" : "有票");
        return flight;
    }
}
