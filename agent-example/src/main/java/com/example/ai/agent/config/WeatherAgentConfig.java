package com.example.ai.agent.config;

import com.example.ai.agent.interceptor.SimpleLoggingAgentInterceptor;
import com.example.ai.agent.tool.WeatherMockData;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/**
 * 天气查询 SimpleAgent 装配（工具调用示例）
 * <p>
 * 通过 {@link FunctionToolDesc} 声明本地 mock 天气工具（编程式，参数 Schema 显式可控），
 * 经 {@code defaultToolAdd} 挂到 SimpleAgent 上；框架 autoToolCall 默认开启，
 * 模型会自动发起工具调用，工具结果回传后由模型汇总为最终 JSON 输出。
 * <p>
 * 说明：本示例刻意不使用 outputSchema（其会触发 response_format=json_object，
 * 与工具调用同开时部分 OpenAI 兼容服务会拒绝），改为在指令中约束输出 JSON 结构，
 * 由 {@code SimpleResponse.toBean} 容错解析。
 */
@Configuration
public class WeatherAgentConfig {

    /** 天气查询工具名（与指令中引用保持一致） */
    public static final String WEATHER_TOOL_NAME = "get_weather_by_city_and_date";

    /**
     * 天气助手指令：强制先调工具取数、禁止编造，并约束最终 JSON 输出结构
     */
    private static final String WEATHER_INSTRUCTION = ""
            + "你是天气查询助手。用户提供城市与日期后，必须先调用工具 " + WEATHER_TOOL_NAME
            + " 获取天气数据，禁止凭记忆或想象编造天气。\n"
            + "拿到工具返回的 JSON 数据后，仅提取其中三项：天气状况（weather）、最低气温（minTemperature）、"
            + "最高气温（maxTemperature），严格按以下 JSON 结构输出，不要输出任何额外解释文字：\n"
            + "{\"weather\":\"天气状况文本\",\"minTemperature\":整数,\"maxTemperature\":整数}\n"
            + "气温为整数（摄氏度），不带单位。";

    /**
     * 本地 mock 天气查询工具（编程式声明：name + 参数 Schema + 处理器）
     */
    @Bean
    public FunctionTool weatherFunctionTool() {
        return new FunctionToolDesc(WEATHER_TOOL_NAME)
                .title("天气查询")
                .description("查询指定城市在指定日期的天气情况（本地模拟数据源），"
                        + "返回天气状况、最低气温、最高气温。")
                .stringParamAdd("city", "城市名称，如：北京")
                .stringParamAdd("date", "查询日期，格式 yyyy-MM-dd")
                .doHandle(args -> WeatherMockData.query(
                        Objects.toString(args.get("city"), ""),
                        Objects.toString(args.get("date"), "")));
    }

    /**
     * 天气查询 SimpleAgent：绑定 ChatModel + mock 天气工具
     */
    @Bean
    public SimpleAgent weatherAgent(ChatModel chatModel, FunctionTool weatherFunctionTool) {
        return SimpleAgent.of(chatModel)
                .name("weather_agent")
                .role("天气查询助手")
                .instruction(WEATHER_INSTRUCTION)
                .defaultToolAdd(weatherFunctionTool)
                .defaultInterceptorAdd(new SimpleLoggingAgentInterceptor())
                .modelOptions(options -> options.temperature(0.1d))
                .retryConfig(3, 1000)
                .sessionWindowSize(0)
                .build();
    }
}
