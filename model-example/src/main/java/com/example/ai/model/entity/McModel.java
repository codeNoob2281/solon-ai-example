package com.example.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置表（mc_model）
 * <p>
 * 一行 = 一个可被聊天页选择的模型（名称 + 显示名 + 窗口信息 + 推理档位）。
 * {@code providerId} 关联 {@code mc_provider.id}（供应商删除时级联删除）。
 * <p>
 * 推理档位（{@code reasoningLevels}）为 JSON 数组，元素形如
 * {@code {"key":"low","label":"低","apiValue":"low"}}：
 * key 为前端稳定标识（off/low/medium/high 或自定义），label 为展示名，
 * apiValue 是实际下发给供应商的 API 参数值（空或 off 表示关闭思考）。
 */
@Data
@TableName("mc_model")
public class McModel {

    /** 模型唯一标识（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属供应商 id（mc_provider.id） */
    private Long providerId;

    /** 模型名称（调用时下发的 model 字段，如 gpt-4o / deepseek-chat） */
    private String modelName;

    /** 显示名称（聊天页选择器展示；为空时回落 modelName） */
    private String displayName;

    /** 上下文窗口（tokens，0 表示未知） */
    private Integer contextWindow;

    /** 最大输出 token 数（0 表示未知） */
    private Integer maxOutputTokens;

    /** 输入类型：text / text,image（多选逗号分隔，决定是否支持图片输入） */
    private String inputTypes;

    /** 预设推理档位（JSON 数组，见类注释；空表示仅「默认」一档） */
    private String reasoningLevels;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
