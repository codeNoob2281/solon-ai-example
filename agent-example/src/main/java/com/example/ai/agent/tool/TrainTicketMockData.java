package com.example.ai.agent.tool;

import com.alibaba.fastjson2.JSON;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 火车票本地 Mock 数据源（不调用真实 12306 API）
 * <p>
 * 以 {@code departureCity#targetCity#date} 的哈希做确定性伪随机：同一查询结果稳定。
 * 返回 3~5 个车次（G/D 混合），含出发/到达时刻、历时、二等/一等座票价与余票；
 * 少量车次无票，便于演示模型的筛选与兜底逻辑。
 */
public final class TrainTicketMockData {

    /** 固定发车时刻池（覆盖早中晚） */
    private static final String[] DEPARTURE_SLOTS = {
            "06:36", "07:58", "09:15", "11:40", "13:26", "15:08", "16:55", "18:40", "19:35", "20:15"
    };

    private TrainTicketMockData() {
    }

    /**
     * 查询火车票（Mock 实现）
     *
     * @param departureCity 出发城市
     * @param targetCity    目标城市
     * @param date          乘车日期（yyyy-MM-dd）
     * @return 车票列表 JSON 字符串
     */
    public static String query(String departureCity, String targetCity, String date) {
        String key = (departureCity == null ? "" : departureCity) + "#"
                + (targetCity == null ? "" : targetCity) + "#"
                + (date == null ? "" : date);
        int seed = Math.abs(Objects.hashCode(key));

        int count = 3 + seed % 3; // 3 ~ 5 个车次
        List<Map<String, Object>> trains = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int varSeed = seed + i * 137;
            trains.add(buildTrain(varSeed, (seed / 7 + i * 3) % DEPARTURE_SLOTS.length,
                    departureCity, targetCity));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("departureCity", departureCity);
        data.put("targetCity", targetCity);
        data.put("date", date);
        data.put("trains", trains);
        return JSON.toJSONString(data);
    }

    private static Map<String, Object> buildTrain(int varSeed, int slotIndex, String departureCity, String targetCity) {
        String departureTime = DEPARTURE_SLOTS[slotIndex];
        int durationMinutes = 240 + varSeed % 240; // 4 ~ 8 小时

        LocalTime departure = LocalTime.parse(departureTime);
        LocalTime arrival = departure.plusMinutes(durationMinutes);
        boolean nextDay = arrival.isBefore(departure); // plusMinutes 跨天回绕即次日到达

        String trainNo = ((varSeed & 1) == 0 ? "G" : "D") + (1000 + varSeed % 4000);
        int secondClassPrice = 120 + varSeed % 480;          // 二等座 120 ~ 599 元
        int firstClassPrice = (int) (secondClassPrice * 1.6); // 一等座约 1.6 倍
        int secondClassSeats = (varSeed % 6 == 0) ? 0 : 100 + varSeed % 160; // 约 1/6 概率无票

        Map<String, Object> train = new LinkedHashMap<>();
        train.put("trainNo", trainNo);
        train.put("departureStation", departureCity + "南站");
        train.put("arrivalStation", targetCity + "南站");
        train.put("departureTime", departureTime);
        train.put("arrivalTime", arrival.toString());
        train.put("arrivalNote", nextDay ? "次日到达" : "当日到达");
        train.put("duration", (durationMinutes / 60) + "小时" + (durationMinutes % 60) + "分");
        train.put("secondClassPrice", secondClassPrice);
        train.put("firstClassPrice", firstClassPrice);
        train.put("secondClassSeats", secondClassSeats);
        train.put("status", secondClassSeats == 0 ? "无票" : "有票");
        return train;
    }
}
