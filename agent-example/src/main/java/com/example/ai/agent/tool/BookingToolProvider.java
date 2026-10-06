package com.example.ai.agent.tool;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 机票/火车票订购 Mock 工具（纯模拟，不涉及真实支付、钱包、余票扣减）
 * <p>
 * 统一入口 {@link #bookTicket}，根据 {@code ticketType} 区分火车票与机票；
 * 订购结果按概率随机生成，便于 Agent 学习处理成功/失败分支。
 */
@Slf4j
@Component
public class BookingToolProvider extends AbsToolProvider {

    /**
     * 订购交通票（火车票 / 机票）
     *
     * @param ticketType      票种：{@code train} 火车票，{@code flight} 机票
     * @param departureCity   出发城市，如：北京
     * @param targetCity      目标城市，如：上海
     * @param date            出行日期，格式 yyyy-MM-dd
     * @param number          车次号/航班号，如：G102、CA1234
     * @param passengerName   乘客姓名
     * @return 订购结果 JSON，包含订单号、状态、金额、失败原因（如有）
     */
    @ToolMapping(name = "book_ticket", description = "订购火车票或机票（本地模拟）。"
            + "参数 ticketType 为 train 时订购火车票，为 flight 时订购机票；"
            + "返回订单结果，包含是否成功、订单号、金额与失败原因。")
    public static String bookTicket(@Param(description = "票种：train 火车票，flight 机票") String ticketType,
                                    @Param(description = "出发城市名称，如：北京") String departureCity,
                                    @Param(description = "目标城市名称，如：上海") String targetCity,
                                    @Param(description = "出行日期，格式 yyyy-MM-dd") String date,
                                    @Param(description = "车次号或航班号，如：G102、CA1234") String number,
                                    @Param(description = "乘客姓名") String passengerName) {
        log.info("[BookingTool] book_ticket 请求: ticketType={}, departureCity={}, targetCity={}, date={}, number={}, passengerName={}",
                ticketType, departureCity, targetCity, date, number, passengerName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketType", ticketType);
        result.put("departureCity", departureCity);
        result.put("targetCity", targetCity);
        result.put("date", date);
        result.put("number", number);
        result.put("passengerName", passengerName);

        int rand = ThreadLocalRandom.current().nextInt(100);
        if (rand < 70) {
            // 70% 订购成功
            int price = mockPrice(ticketType, number);
            String orderNo = generateOrderNo(ticketType);
            result.put("status", "success");
            result.put("orderNo", orderNo);
            result.put("price", price);
            result.put("failReason", null);
        } else if (rand < 90) {
            // 20% 无票订购失败
            result.put("status", "failed");
            result.put("orderNo", null);
            result.put("price", null);
            result.put("failReason", "该班次已售罄，暂无可售席位");
        } else {
            // 10% 钱包余额不足
            result.put("status", "failed");
            result.put("orderNo", null);
            result.put("price", null);
            result.put("failReason", "钱包余额不足，请先充值后再试");
        }

        String json = JSON.toJSONString(result);
        log.info("[BookingTool] book_ticket 响应: {}", json);
        return json;
    }

    /**
     * 生成模拟订单号
     */
    private static String generateOrderNo(String ticketType) {
        String prefix = "train".equalsIgnoreCase(ticketType) ? "TR" : "FL";
        long now = System.currentTimeMillis();
        return prefix + now + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    /**
     * 生成模拟票价（仅用于成功场景展示，不进行真实支付）
     */
    private static int mockPrice(String ticketType, String number) {
        int seed = Math.abs(number == null ? 0 : number.hashCode());
        if ("train".equalsIgnoreCase(ticketType)) {
            return 120 + seed % 480; // 火车票 120 ~ 599 元
        } else {
            return 380 + seed % 920; // 机票 380 ~ 1299 元
        }
    }
}
