package com.example.ai.agent.dto;

import lombok.Data;

import java.util.Map;

/**
 * 人工审批提交请求体
 */
@Data
public class HitlSubmitRequest {
    /**
     * 会话 id
     */
    private String sessionId;

    /**
     * 工具调用实例 id（callUuid）；为空时取当前会话首个待审批任务
     */
    private String callUuid;

    /**
     * 审批动作：1=批准，2=拒绝，3=跳过
     */
    private Integer action;

    /**
     * 审批备注（拒绝理由等）
     */
    private String comment;

    /**
     * 修正后的参数（批准时可选）
     */
    private Map<String, Object> modifiedArgs;
}
