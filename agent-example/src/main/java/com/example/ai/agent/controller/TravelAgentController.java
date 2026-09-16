package com.example.ai.agent.controller;

import com.example.ai.agent.dto.TravelPlanRequest;
import com.example.ai.agent.dto.TravelPlanResponse;
import com.example.ai.agent.service.TravelPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 出行规划接口（ReActAgent 示例：一句话输入 + Agent 层意图澄清）
 * <pre>
 * POST /agent/travel/plan   出行规划（意图识别 → 要素澄清（多轮） → 计划模式 → 天气/路线/火车票/机票 → 综合建议）
 * </pre>
 */
@Slf4j
@Tag(name = "出行规划", description = "基于 solon-ai-agent ReActAgent 的出行规划示例："
        + "用户一句话输入，Agent 层完成意图识别与要素澄清（needClarification=true 时回传 sessionId 续聊），"
        + "要素齐备后 planningMode 拆解计划并 ReAct 循环调用本地 Mock 工具"
        + "（两地天气/路线概况/火车票/机票），输出结构化出行建议")
@RestController
@RequestMapping("/agent/travel")
public class TravelAgentController {

    @Resource
    private TravelPlanService travelPlanService;

    /**
     * 出行规划：POST /agent/travel/plan
     * <pre>{@code
     * // 第一轮：一句话输入（信息齐备时直接规划，缺失时返回追问）
     * 请求：{ "message": "我明天下午6点前要从北京到上海，帮我规划一下" }
     * 响应A（齐备）：{ "sessionId": "uuid", "needClarification": false, "plan": { "recommendedType": "train", ... } }
     * 响应B（缺失）：{ "sessionId": "uuid", "needClarification": true,
     *                "missingFields": ["departureCity"], "clarificationMessage": "请问您从哪个城市出发？" }
     *
     * // 第二轮：携带 sessionId 补充信息，Agent 结合历史继续
     * 请求：{ "message": "从北京出发", "sessionId": "uuid" }
     * 响应：{ "sessionId": "uuid", "needClarification": false, "plan": { ... } }
     * }</pre>
     */
    @Operation(summary = "出行规划（ReActAgent 意图澄清 + 规划模式 + Mock 工具调用）",
            description = "一句话自然语言输入，由 Agent 完成意图识别与三要素（出发城市/目标城市/期望到达时刻）澄清；"
                    + "要素齐备后先 create_plan 拆解计划，再按计划依次查询两地天气、路线概况、"
                    + "火车票与机票（均为本地 Mock 数据），综合对比后输出结构化出行建议")
    @PostMapping("/plan")
    public TravelPlanResponse plan(@RequestBody TravelPlanRequest request) {
        log.info("[Travel] 收到规划请求: message={}, sessionId={}",
                request == null ? null : request.getMessage(),
                request == null ? null : request.getSessionId());
        return travelPlanService.plan(request);
    }
}
