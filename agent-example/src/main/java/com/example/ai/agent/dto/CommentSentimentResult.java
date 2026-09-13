package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户评论情感分析结果（五维度）
 * <p>
 * 各维度取值为情感标签：POS（正向）/ NEG（负向）/ NTU（中性）/ UNKNOW（无法分析）。
 * 作为 SimpleAgent 的输出 Schema（outputSchema）注入提示词，约束模型按该结构输出 JSON。
 */
@Data
@Schema(description = "评论情感分析结果（五维度情感标签：POS 正向 / NEG 负向 / NTU 中性 / UNKNOW 无法分析）")
public class CommentSentimentResult {

    @Schema(description = "商品属性情感（功能/规格/材质等属性是否匹配预期）",
            allowableValues = {"POS", "NEG", "NTU", "UNKNOW"}, example = "UNKNOW")
    private String productAttributes;

    @Schema(description = "商品质量情感（做工/用料/耐用度/瑕疵等）",
            allowableValues = {"POS", "NEG", "NTU", "UNKNOW"}, example = "NEG")
    private String productQuality;

    @Schema(description = "外观设计情感（颜值/配色/造型/包装等）",
            allowableValues = {"POS", "NEG", "NTU", "UNKNOW"}, example = "POS")
    private String appearanceDesign;

    @Schema(description = "使用体验情感（上手难度/操作便捷性/实际感受等）",
            allowableValues = {"POS", "NEG", "NTU", "UNKNOW"}, example = "NTU")
    private String userExperience;

    @Schema(description = "价格价值情感（性价比/价格与价值匹配度等）",
            allowableValues = {"POS", "NEG", "NTU", "UNKNOW"}, example = "POS")
    private String costPerformance;
}
