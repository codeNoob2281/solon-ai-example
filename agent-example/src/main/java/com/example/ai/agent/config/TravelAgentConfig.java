package com.example.ai.agent.config;

import com.example.ai.agent.interceptor.ReActLoggingAgentInterceptor;
import com.example.ai.agent.tool.TravelToolProvider;
import com.example.ai.agent.tool.WeatherQueryToolProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 出行规划 ReActAgent 装配（ReAct = Reason + Act 循环推理示例）
 * <p>
 * 用户以一句话自然语言输入，<b>意图识别、信息澄清、出行规划全部在 Agent 层完成</b>：
 * <ul>
 *   <li>意图识别：Agent 从用户输入（含多轮历史）抽取三要素（出发城市/目标城市/期望到达时刻）；</li>
 *   <li>意图澄清：要素缺失时 Agent 不调工具、直接输出澄清 JSON 追问，
 *       服务层以 {@code InMemoryAgentSession} 按 sessionId 维持多轮会话；</li>
 *   <li>规划执行：要素齐备后 planningMode(true) 注入 create_plan 等计划工具，
 *       Agent 先拆解计划，再按 ReAct 循环依次调用本地 Mock 工具
 *       （两地天气 / 路线概况 / 火车票 / 机票），输出结构化 JSON 建议。</li>
 * </ul>
 * 指令经 {@code instruction(Function)} 动态构建：每次推理在系统提示词<b>末尾</b>注入当前日期
 * （动态内容置于末尾，静态任务指令在前），供 Agent 将"今天/明天"等相对日期换算为具体 travelDate。
 * <p>
 * 说明：刻意不使用 outputSchema（其会触发 response_format=json_object，
 * 与工具调用同开时部分 OpenAI 兼容服务会拒绝），改为在指令中约束输出 JSON 结构
 * （澄清/规划双形态，见 TravelPlanResponse），由服务层容错解析。
 */
@Configuration
public class TravelAgentConfig {

    /**
     * 路线概况工具名（与指令中引用保持一致）
     */
    public static final String ROUTE_TOOL_NAME = "query_route";

    /**
     * 火车票查询工具名
     */
    public static final String TRAIN_TOOL_NAME = "query_train_tickets";

    /**
     * 机票查询工具名
     */
    public static final String FLIGHT_TOOL_NAME = "query_flight_tickets";

    /**
     * 当前日期占位符（每次推理由 instruction(Function) 动态替换）
     */
    private static final String CURRENT_DATE_PLACEHOLDER = "{{currentDate}}";

    /**
     * 规划模式拆解策略建议（planningInstruction，追加在框架任务规划指南之后）
     */
    private static final String TRAVEL_PLANNING_INSTRUCTION = ""
            + "出行规划任务建议拆解为：1) 查询出发城市与目标城市当天天气；2) 查询两城间路线概况；"
            + "3) 查询火车票；4) 查询机票；5) 综合对比给出最终建议。";

    /**
     * 出行规划助手指令模板：意图识别 → 意图澄清 → 规划执行 → 筛选建议 → 最终 JSON 输出契约
     */
    private static final String TRAVEL_INSTRUCTION_TEMPLATE = ""
            + "你是出行规划助手。用户会用一句自然语言提出出行需求（可能口语化、信息不全），"
            + "你负责意图识别、信息澄清与出行规划。\n\n"
            + "## 工作流程\n"
            + "第一步【意图识别】：从用户输入及历史对话中抽取三要素：\n"
            + "- departureCity：出发城市；\n"
            + "- targetCity：目标城市；\n"
            + "- arriveTime：期望到达目标城市的时刻（24小时制 HH:mm，如 \"18:00\"）；\n"
            + "- travelDate：出行日期，由\"今天/明天\"等相对日期换算（以末尾【当前日期】为准）；"
            + "用户未说明出行日期时，默认为明天。\n"
            + "多轮对话时，必须结合历史对话中用户已提供的信息判断要素是否齐备，不要重复询问已回答的内容。\n\n"
            + "第二步【意图澄清】：三要素中任一缺失或无法从上下文确定时：\n"
            + "禁止调用任何工具（包括计划工具 create_plan 等），直接输出澄清 JSON；"
            + "一次问全所有缺失项，不要逐个追问。示例：\n"
            + "{\"needClarification\":true,\"missingFields\":[\"departureCity\"],"
            + "\"clarificationMessage\":\"请问您从哪个城市出发？\",\"plan\":null}\n\n"
            + "第三步【规划执行】：三要素齐备后，先用 create_plan 拆解执行计划，再严格按计划逐步执行：\n"
            + "1. 用 get_weather_by_city_and_date 分别查询出发城市与目标城市在 travelDate 当天的天气；\n"
            + "2. 用 " + ROUTE_TOOL_NAME + " 查询两城间路线概况（距离、是否有直达高铁/直飞）；\n"
            + "3. 用 " + TRAIN_TOOL_NAME + " 查询 travelDate 当天火车票；\n"
            + "4. 用 " + FLIGHT_TOOL_NAME + " 查询 travelDate 当天机票；\n"
            + "5. 综合对比所有数据后给出最终建议。\n"
            + "所有结论必须基于工具返回数据（Observation），严禁编造车次、航班、价格、余票或天气。\n\n"
            + "## 筛选与建议规则\n"
            + "1. 候选项（含次日到达）必须不晚于期望到达时间（travelDate 当天 arriveTime 时刻前）；\n"
            + "2. 排序优先级：能按时到达 > 余票/余座充足 > 历时短 > 价格低；\n"
            + "3. 若所有车次与航班都无法按时到达，选择到达时间最接近的，并在理由中明确说明风险；\n"
            + "4. 结合两地天气给出提醒（如降雨提醒带伞、预留去车站/机场的缓冲时间）。\n\n"
            + "## 最终输出（必须遵守）\n"
            + "始终输出纯 JSON：不要 markdown 代码块标记，不要任何解释文字。两种形态二选一：\n"
            + "1) 需要澄清：{\"needClarification\":true,\"missingFields\":[\"缺失要素英文名\"],"
            + "\"clarificationMessage\":\"中文追问\",\"plan\":null}\n"
            + "2) 规划完成：{\"needClarification\":false,\"missingFields\":null,\"clarificationMessage\":null,"
            + "\"plan\":{\"departureCity\":\"北京\",\"targetCity\":\"上海\",\"travelDate\":\"2026-09-20\","
            + "\"arriveTime\":\"18:00\",\"departureWeather\":\"多云\",\"targetWeather\":\"小雨\",\"distanceKm\":1200,"
            + "\"recommendedType\":\"train\",\"recommendedOption\":{\"type\":\"train\",\"code\":\"G1024\","
            + "\"departureTime\":\"08:15\",\"arrivalTime\":\"14:36\",\"duration\":\"6小时21分\",\"price\":\"553\","
            + "\"seatInfo\":\"二等座余票120张\",\"note\":\"当日到达\"},"
            + "\"reason\":\"一句话推荐理由\",\"alternatives\":[],\"reminders\":[\"提醒1\",\"提醒2\"]}}\n"
            + "约束：recommendedType 只能是 train 或 flight；alternatives 为同结构对象的数组（可为空）；"
            + "price 为不带单位的数字字符串；reminders 至少 1 条。\n\n"
            + "## 当前日期\n"
            + "{{currentDate}}\n"
            + "用户提到的\"今天/明天/后天\"等相对日期，以上述日期为准换算为 yyyy-MM-dd 具体日期（作为 travelDate）；"
            + "用户未说明出行日期时，默认 travelDate 为明天。";

    /**
     * 当前日期行（含星期），每次推理动态计算
     */
    private static String currentDateLine() {
        LocalDate today = LocalDate.now();
        String[] weekNames = {"一", "二", "三", "四", "五", "六", "日"};
        return "今天是 " + today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "（星期" + weekNames[today.getDayOfWeek().getValue() - 1] + "）。";
    }

    /**
     * 出行规划 ReActAgent：绑定 ChatModel + 本地 Mock 工具 + 计划模式
     */
    @Bean
    public ReActAgent travelAgent(ChatModel chatModel,
                                  WeatherQueryToolProvider weatherQueryToolProvider,
                                  TravelToolProvider travelToolProvider) {
        return ReActAgent.of(chatModel)
                .name("travel_agent")
                .role("出行规划助手")
                // 动态指令：每次推理注入当前日期（相对日期换算依据）
                .instruction(trace -> TRAVEL_INSTRUCTION_TEMPLATE
                        .replace(CURRENT_DATE_PLACEHOLDER, currentDateLine()))
                .defaultToolAdd(weatherQueryToolProvider)
                .defaultToolAdd(travelToolProvider)
                .defaultInterceptorAdd(new ReActLoggingAgentInterceptor())
                .planningMode(true)
                .planningInstruction(TRAVEL_PLANNING_INSTRUCTION)
                .modelOptions(options -> options.temperature(0.1d))
                .retryConfig(3, 1000)
                .maxTurns(20)
                // 多轮澄清会话：放宽历史窗口（默认 8），避免前次规划的较长消息挤掉澄清上下文
                .sessionWindowSize(20)
                .build();
    }
}
