package com.example.ai.agent.tool;

import com.example.ai.agent.config.TravelAgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Component;

/**
 * 出行规划本地 Mock 工具集（路线概况 / 火车票 / 机票）
 * <p>
 * 与 {@link WeatherQueryToolProvider}（复用，查两地天气）共同构成出行规划 Agent 的
 * 全部外部数据来源；声明式 {@code @ToolMapping} + {@code @Param}，由框架自动收集为工具。
 * 每次调用打印请求参数与 mock 响应，便于观察 Agent 的取数轨迹。
 */
@Slf4j
@Component
public class TravelToolProvider extends AbsToolProvider {

    @ToolMapping(name = TravelAgentConfig.ROUTE_TOOL_NAME, description = "查询两个城市之间的交通路线概况（本地模拟数据源），"
            + "返回公路距离（distanceKm）、自驾时长（drivingDuration）、过路费（tollCost）、"
            + "是否有直达高铁（hasDirectTrain）与直飞航班（hasDirectFlight）。")
    public static String queryRoute(@Param(description = "出发城市名称，如：北京") String departureCity,
                                    @Param(description = "目标城市名称，如：上海") String targetCity) {
        log.info("[TravelTool] query_route 请求: departureCity={}, targetCity={}", departureCity, targetCity);
        String result = RouteMockData.query(departureCity, targetCity);
        log.info("[TravelTool] query_route 响应: {}", result);
        return result;
    }

    @ToolMapping(name = TravelAgentConfig.TRAIN_TOOL_NAME, description = "查询指定日期两城之间的火车票（本地模拟数据源），"
            + "返回车次列表：车次号（trainNo）、出发/到达车站与时刻、历时、二等/一等座票价与余票数量、是否有票。")
    public static String queryTrainTickets(@Param(description = "出发城市名称，如：北京") String departureCity,
                                           @Param(description = "目标城市名称，如：上海") String targetCity,
                                           @Param(description = "乘车日期，格式 yyyy-MM-dd") String date) {
        log.info("[TravelTool] query_train_tickets 请求: departureCity={}, targetCity={}, date={}",
                departureCity, targetCity, date);
        String result = TrainTicketMockData.query(departureCity, targetCity, date);
        log.info("[TravelTool] query_train_tickets 响应: {}", result);
        return result;
    }

    @ToolMapping(name = TravelAgentConfig.FLIGHT_TOOL_NAME, description = "查询指定日期两城之间的机票（本地模拟数据源），"
            + "返回航班列表：航班号（flightNo）、航司、出发/到达机场与时刻、飞行时长、经济舱价格、折扣与余座数量。")
    public static String queryFlightTickets(@Param(description = "出发城市名称，如：北京") String departureCity,
                                            @Param(description = "目标城市名称，如：上海") String targetCity,
                                            @Param(description = "乘机日期，格式 yyyy-MM-dd") String date) {
        log.info("[TravelTool] query_flight_tickets 请求: departureCity={}, targetCity={}, date={}",
                departureCity, targetCity, date);
        String result = FlightTicketMockData.query(departureCity, targetCity, date);
        log.info("[TravelTool] query_flight_tickets 响应: {}", result);
        return result;
    }
}
