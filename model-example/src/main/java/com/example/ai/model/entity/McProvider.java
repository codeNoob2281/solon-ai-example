package com.example.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型供应商表（mc_provider）
 * <p>
 * 一行 = 一个 OpenAI 兼容协议的模型服务商配置（Base URL + API Key）。
 * 供应商下可维护多个模型（mc_model），删除供应商时级联删除其全部模型。
 * <p>
 * 说明：{@code apiKey} 明文存库（本机演示用），接口回显一律脱敏。
 */
@Data
@TableName("mc_provider")
public class McProvider {

    /** 供应商唯一标识（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商名称（唯一，聊天页按此分组展示） */
    private String name;

    /** 供应商类型：openai / deepseek / qwen / moonshot / zhipu / ollama / custom（决定默认 Base URL 与调用方言） */
    private String type;

    /** 接口地址（OpenAI 兼容 Base URL，不带 /chat/completions） */
    private String baseUrl;

    /** API Key（明文存储，接口回显脱敏） */
    private String apiKey;

    /** 状态：1 启用 / 0 禁用（禁用后聊天页不再展示其模型） */
    private Integer status;

    /** 备注 */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
