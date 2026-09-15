package com.example.ai.agent.tool;

import com.example.ai.agent.config.WeatherAgentConfig;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Component;


/**
 *
 *
 * @author <a href="mailto:huangzf1778@fingard.com">huang zifan</a>
 * @version 1.0
 * @projectName: solon-ai-example
 * @date 2026/9/14
 */
@Component
public class WeatherQueryToolProvider extends AbsToolProvider {

    @ToolMapping(name = WeatherAgentConfig.WEATHER_TOOL_NAME, description = "查询指定城市在指定日期的天气情况（本地模拟数据源），"
            + "返回天气状况、最低气温、最高气温。")
    public static String query(@Param(description = "城市名称，如：北京") String city,
                               @Param(description = "查询日期，格式 yyyy-MM-dd") String date) {
        return WeatherMockData.query(city, date);
    }
}
