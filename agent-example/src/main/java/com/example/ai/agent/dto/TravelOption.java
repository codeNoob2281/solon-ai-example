package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 出行候选方案（车次/航班统一抽象，ReActAgent 出行规划示例）
 * <p>
 * 字段统一为字符串以容错解析模型输出（价格/时刻等由模型从工具 Observation 中摘取回填）。
 */
@Data
@Schema(description = "出行候选方案（车次/航班统一抽象）")
public class TravelOption {

    @Schema(description = "类型：train=火车 / flight=飞机", example = "train")
    private String type;

    @Schema(description = "车次号或航班号", example = "G1024")
    private String code;

    @Schema(description = "出发时刻（HH:mm）", example = "08:15")
    private String departureTime;

    @Schema(description = "到达时刻（HH:mm，跨天见 note）", example = "14:36")
    private String arrivalTime;

    @Schema(description = "全程历时", example = "6小时21分")
    private String duration;

    @Schema(description = "参考价格（数字字符串，元）", example = "553")
    private String price;

    @Schema(description = "余票/余座信息", example = "二等座余票120张")
    private String seatInfo;

    @Schema(description = "备注（当日到达/次日到达/折扣等）", example = "当日到达")
    private String note;
}
