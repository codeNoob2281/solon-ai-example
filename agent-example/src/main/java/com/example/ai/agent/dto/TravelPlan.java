package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 出行规划结果（ReActAgent 依次查询天气/路线/火车票/机票后生成的结构化建议）
 */
@Data
@Schema(description = "出行规划结果（综合天气/路线/火车票/机票后的建议）")
public class TravelPlan {

    @Schema(description = "出发城市", example = "北京")
    private String departureCity;

    @Schema(description = "目标城市", example = "上海")
    private String targetCity;

    @Schema(description = "出行日期（yyyy-MM-dd）", example = "2026-09-20")
    private String travelDate;

    @Schema(description = "期望到达时刻", example = "18:00")
    private String arriveTime;

    @Schema(description = "出发城市当天天气", example = "多云")
    private String departureWeather;

    @Schema(description = "目标城市当天天气", example = "小雨")
    private String targetWeather;

    @Schema(description = "两城间公路距离（公里）", example = "1200")
    private Integer distanceKm;

    @Schema(description = "推荐出行方式：train / flight", example = "train")
    private String recommendedType;

    @Schema(description = "推荐方案（车次/航班明细）")
    private TravelOption recommendedOption;

    @Schema(description = "推荐理由", example = "G1024 于 14:36 到达，早于期望到达时间 18:00，且票价低于航班")
    private String reason;

    @Schema(description = "备选方案（可为空数组）")
    private List<TravelOption> alternatives;

    @Schema(description = "出行提醒（天气/缓冲时间等）")
    private List<String> reminders;
}
