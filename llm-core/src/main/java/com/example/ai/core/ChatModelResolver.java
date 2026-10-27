package com.example.ai.core;

import org.noear.solon.ai.chat.ChatModel;

/**
 * 对话模型解析契约（llm-core 提供接口，具体实现由业务模块注册）
 * <p>
 * 目的：让「按请求切换模型」的能力与「模型配置存储」解耦——
 * agent-example 只依赖本接口（llm-core），模型配置的具体来源（DB / 配置中心）
 * 由 model-example 模块实现并注册为 Bean；未注册实现时业务侧按缺省处理
 * （走 {@code .env} 装配的默认 ChatModel，即聊天页的「默认」选项）。
 * <p>
 * 调用方约定（agent-example）：
 * <ul>
 *   <li>请求未指定 modelId → 不覆盖模型，直接用默认 ChatModel；</li>
 *   <li>请求指定 modelId → 用 {@link ResolvedChatModel#getChatModel()} 覆盖本次请求的模型；</li>
 *   <li>推理档位命中时按 {@link ResolvedChatModel#getReasoningApiValue()} 决定下发值：
 *       空 → 不下发任何推理参数（使用供应商默认行为）；
 *       非空 → 作为 {@code reasoning_effort} 下发（由 Solon AI 按模型归一化）。</li>
 * </ul>
 */
public interface ChatModelResolver {

    /**
     * 解析模型
     *
     * @param modelId           模型配置 id（可为字符串形式的主键）
     * @param reasoningLevelKey 推理档位 key（可空：空表示不覆盖推理参数）
     * @return 解析结果；模型不存在或已禁用时返回 {@code null}
     */
    ResolvedChatModel resolve(String modelId, String reasoningLevelKey);

    /**
     * 模型解析结果：本次请求实际使用的 ChatModel + 推理档位对应的 API 参数值
     */
    class ResolvedChatModel {

        /** 本次请求使用的对话模型（按模型配置构建） */
        private final ChatModel chatModel;

        /** 命中的推理档位 key（可空） */
        private final String reasoningLevelKey;

        /** 推理档位对应的 API 参数值（可空：空 = 不下发推理参数） */
        private final String reasoningApiValue;

        public ResolvedChatModel(ChatModel chatModel, String reasoningLevelKey, String reasoningApiValue) {
            this.chatModel = chatModel;
            this.reasoningLevelKey = reasoningLevelKey;
            this.reasoningApiValue = reasoningApiValue;
        }

        public ChatModel getChatModel() {
            return chatModel;
        }

        public String getReasoningLevelKey() {
            return reasoningLevelKey;
        }

        public String getReasoningApiValue() {
            return reasoningApiValue;
        }
    }
}
