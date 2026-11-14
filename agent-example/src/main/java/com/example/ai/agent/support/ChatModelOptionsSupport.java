package com.example.ai.agent.support;

import com.example.ai.core.ChatModelResolver;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

/**
 * 按请求切换对话模型的公共支持（AI SDK 对话页与出行规划工作台共用）
 * <p>
 * 两个控制器的模型切换语义完全一致，抽到这里避免重复：
 * <ul>
 *   <li>{@link #resolve} —— 请求未指定 modelId 时返回 {@code null}（不覆盖模型，继续走默认
 *       ChatModel）；指定时经 {@link ChatModelResolver} 解析（model-example 提供实现，未引入时为空）；</li>
 *   <li>{@link #optionsAdjustor} —— 构造本次请求的选项修正器，把模型与推理档位下发到 ReAct 请求。</li>
 * </ul>
 */
@Slf4j
public final class ChatModelOptionsSupport {

    private ChatModelOptionsSupport() {
    }

    /**
     * 解析本次请求使用的模型
     * <ul>
     *   <li>未指定 modelId：不需要覆盖模型，返回 {@code null}（继续走默认 ChatModel）；</li>
     *   <li>指定 modelId：由 {@link ChatModelResolver} 解析（modelId 非法 / 模型禁用 → null，
     *       由调用方按协议错误帧回包）；</li>
     *   <li>未引入模型配置模块（无 {@link ChatModelResolver} Bean）时按「模型不可用」处理。</li>
     * </ul>
     *
     * @param provider          ChatModelResolver 提供者（可为空）
     * @param modelId           请求的模型配置 id（可空）
     * @param reasoningLevelKey 推理档位 key（可空）
     * @return 解析结果；未指定 modelId 或解析失败时为 {@code null}
     */
    public static ChatModelResolver.ResolvedChatModel resolve(
            ObjectProvider<ChatModelResolver> provider, String modelId, String reasoningLevelKey) {
        String trimmedId = trimToNull(modelId);
        if (trimmedId == null) {
            return null;
        }
        ChatModelResolver resolver = provider == null ? null : provider.getIfAvailable();
        if (resolver == null) {
            log.warn("[Chat Model] 未装配 ChatModelResolver（model-example），无法切换模型: modelId={}", trimmedId);
            return null;
        }
        return resolver.resolve(trimmedId, trimToNull(reasoningLevelKey));
    }

    /**
     * 构造本次请求的选项修正器（每次都返回非 null，用于把上一轮的模型 / 推理参数清干净）
     * <p>
     * 背景：{@code ReActRequest.init()} 在续接同一 {@code AgentSession} 时会从上轮 trace
     * <b>拷贝</b> options，上轮设置的 chatModel / reasoning_effort 会残留。因此这里对每次请求都显式
     * 覆盖全部三项，避免「这一轮选默认，却沿用了上一轮选的模型」这类串档问题。
     * <ul>
     *   <li>chatModel：命中模型配置 → 用该模型；否则回落 Agent 默认模型（.env 装配）；</li>
     *   <li>推理档位 apiValue 为空 → 移除 thinking / reasoning_effort（保持供应商默认行为）；</li>
     *   <li>apiValue 为 {@code off} → 关闭思考（见下方「双重下发」说明）；</li>
     *   <li>其余 → 作为 {@code reasoning_effort} 下发（具体写出的字段由 Solon AI 按模型归一化）。</li>
     * </ul>
     * <p>
     * 「关闭」为何要下发两次：{@code thinking(false)} 由 Solon AI 按<b>已知模型族</b>
     * （qwen/deepseek/glm/kimi/minimax 等）映射成 {@code enable_thinking} / {@code thinking.type}；
     * 未知模型（如 hy4-preview）会走 {@code ThinkingSwitchWire.NONE} 分支静默不写出，
     * 思考照样吃满额度导致正文为空。因此额外用 {@code optionSet} 透传
     * {@code enable_thinking=false} 作为兜底（忽略未知字段的网关无副作用）。
     *
     * @param resolved 模型解析结果（可空：空表示走默认模型）
     * @param fallback Agent 默认模型（未命中模型配置时回落）
     */
    public static Consumer<ReActOptionsAmend> optionsAdjustor(
            ChatModelResolver.ResolvedChatModel resolved, ChatModel fallback) {
        String reasoningValue = trimToNull(resolved == null ? null : resolved.getReasoningApiValue());
        ChatModel selected = resolved == null ? null : resolved.getChatModel();
        boolean disableThinking = reasoningValue != null && "off".equalsIgnoreCase(reasoningValue);

        return adjustor -> {
            ChatModel target = selected != null ? selected : fallback;
            if (target != null) {
                adjustor.chatModel(target);
            }
            // 先清空上轮残留，再按本轮档位写入
            adjustor.thinking(null);
            adjustor.reasoning_effort(null);
            adjustor.optionRemove("enable_thinking");
            if (disableThinking) {
                adjustor.thinking(false);
                adjustor.optionSet("enable_thinking", false);
            } else if (reasoningValue != null) {
                adjustor.reasoning_effort(reasoningValue);
            }
        };
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
