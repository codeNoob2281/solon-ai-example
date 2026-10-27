import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport, UIMessage } from 'ai';
import { Button } from '@/components/ui/button';
import {
  Message,
  MessageContent,
  MessageResponse,
} from '@/components/ai-elements/message';
import {
  Reasoning,
  ReasoningContent,
  ReasoningTrigger,
} from '@/components/ai-elements/reasoning';
import { Shimmer } from '@/components/ai-elements/shimmer';
import {
  ModelSelector,
  ModelSelectorContent,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorInput,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorName,
  ModelSelectorSeparator,
  ModelSelectorTrigger,
} from '@/components/ai-elements/model-selector';
import { CheckIcon, ChevronsUpDownIcon, CpuIcon, SparklesIcon } from 'lucide-react';
import { listChatModelGroups } from '@/lib/modelApi';
import type { ChatModelGroup, ChatModelOption } from '@/lib/modelApi';

/**
 * Solon AI Agent 流式对话页（纯对话 demo，AI SDK 协议）
 *
 * 数据链路：
 *   useChat（DefaultChatTransport，POST /api/agent/chat/stream）
 *   → agent-example AgentChatController（ReActAgent + AiSdkStreamWrapper）
 *   → AI SDK UI Stream Protocol v1 SSE（start/text-delta/finish…）
 *   → 前端逐字渲染
 *
 * 组件映射：
 *   消息 → Message / MessageContent / MessageResponse（Streamdown 渲染 Markdown）
 *   思考 → Reasoning / ReasoningTrigger / ReasoningContent
 *          （流式自动展开、结束后 1s 自动收起，历史消息默认收起可回看）
 *   模型 → ai-elements ModelSelector（Command 列表，按供应商分组；含「默认」项）
 *
 * 模型与思考强度：两者都在输入框左侧的同一个弹窗里选（弹窗底部为「思考深度」分段按钮），
 * 触发按钮外层直接显示「模型名 + 当前思考档位」，无需展开即可确认当前配置。
 * 选择结果随每次请求体下发（modelId / reasoningLevel），后端按模型配置解析
 * （model-example 的 ChatModelResolver）；选「默认」时不下发 modelId，走后台 .env 装配的 ChatModel。
 * 推理档位来自所选模型的 reasoningLevels，切换模型时自动回落到该模型的第一个档位。
 *
 * 多轮会话：chatId 由前端生成，useChat({ id }) 随请求体上报，
 * 后端按 chatId 维持 AgentSession 注入对话历史。
 */
const STREAM_API = '/api/agent/chat/stream';

/** 「默认」模型选项的哨兵 id（不下发 modelId，后端走 .env） */
const DEFAULT_MODEL_VALUE = 'default';

/** 提取 reasoning 分片文本（协议 v1：reasoning part 用 text 字段承载） */
function reasoningTextOf(part: UIMessage['parts'][number]): string {
  if (part.type !== 'reasoning') {
    return '';
  }
  const text = (part as { text?: unknown }).text;
  return typeof text === 'string' ? text : '';
}

function MessageBubble({ message, streaming }: { message: UIMessage; streaming: boolean }) {
  const hasVisible = message.parts.some(
    part =>
      (part.type === 'text' && part.text) ||
      (part.type === 'reasoning' && reasoningTextOf(part)),
  );

  return (
    <Message from={message.role}>
      <MessageContent>
        {message.parts.map((part, index) => {
          if (part.type === 'text' && part.text) {
            return (
              <MessageResponse key={index} className="md">
                {part.text}
              </MessageResponse>
            );
          }
          if (part.type === 'reasoning') {
            const text = reasoningTextOf(part);
            if (!text) {
              return null;
            }
            // 流式中的最后一段 reasoning 视为生成中（自动展开），结束后组件自动收起
            return (
              <Reasoning
                key={index}
                isStreaming={streaming && index === message.parts.length - 1}
              >
                <ReasoningTrigger
                  getThinkingMessage={(isStreaming, duration) =>
                    isStreaming ? (
                      <Shimmer duration={1}>思考中…</Shimmer>
                    ) : duration ? (
                      <p>已思考 {duration} 秒</p>
                    ) : (
                      <p>思考过程</p>
                    )
                  }
                />
                <ReasoningContent className="md">{text}</ReasoningContent>
              </Reasoning>
            );
          }
          // 其余分片（step-start / data-agent-event / 工具分片等）当前页暂不渲染
          return null;
        })}
        {streaming && !hasVisible ? (
          <Shimmer className="text-sm" duration={1.5}>
            正在思考…
          </Shimmer>
        ) : null}
      </MessageContent>
    </Message>
  );
}

export default function ChatDemoPage() {
  // 每个会话一个 chatId；key 变化触发重挂载，即「新对话」
  const [chatId, setChatId] = useState(() => crypto.randomUUID());

  return (
    <ChatPanel
      key={chatId}
      chatId={chatId}
      onNewChat={() => setChatId(crypto.randomUUID())}
    />
  );
}

function ChatPanel({ chatId, onNewChat }: { chatId: string; onNewChat: () => void }) {
  const [input, setInput] = useState('');
  // transport 与组件实例绑定（key 重挂载即新对话），useMemo 避免重建
  const transport = useMemo(() => new DefaultChatTransport({ api: STREAM_API }), []);
  const { messages, sendMessage, status, error, stop } = useChat({ id: chatId, transport });
  const busy = status === 'submitted' || status === 'streaming';
  const listRef = useRef<HTMLDivElement>(null);

  // 模型选择：分组数据 + 当前选中项（'default' = 走后台 .env）
  const [groups, setGroups] = useState<ChatModelGroup[]>([]);
  const [modelValue, setModelValue] = useState<string>(DEFAULT_MODEL_VALUE);
  const [reasoningLevel, setReasoningLevel] = useState<string>('');
  const [selectorOpen, setSelectorOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listChatModelGroups()
      .then(data => {
        if (!cancelled) {
          setGroups(data);
        }
      })
      .catch(() => {
        // 配置服务不可用时静默降级为「仅默认模型」，不影响对话本身
        if (!cancelled) {
          setGroups([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  const selectedModel = useMemo<ChatModelOption | null>(() => {
    if (modelValue === DEFAULT_MODEL_VALUE) {
      return null;
    }
    for (const group of groups) {
      const hit = group.models.find(model => String(model.id) === modelValue);
      if (hit) {
        return hit;
      }
    }
    return null;
  }, [groups, modelValue]);

  /** 当前生效的推理档位（外层触发按钮与弹窗内高亮共用） */
  const activeLevel = useMemo(() => {
    if (!selectedModel) {
      return null;
    }
    return selectedModel.reasoningLevels.find(level => level.key === reasoningLevel) ?? null;
  }, [selectedModel, reasoningLevel]);

  // 切换提示：模型 / 思考档位改动后，在消息区下方灰字提示（发送后即失效清除）
  const [switchNotice, setSwitchNotice] = useState<string | null>(null);

  // 切换模型：推理档位回落到该模型的第一个档位（无档位则清空）
  const pickModel = (value: string, model: ChatModelOption | null) => {
    setModelValue(value);
    const nextLevel = model && model.reasoningLevels.length > 0 ? model.reasoningLevels[0].key : '';
    setReasoningLevel(nextLevel);
    const levelLabel = model?.reasoningLevels.find(level => level.key === nextLevel)?.label;
    setSwitchNotice(
      `已切换到 ${model ? model.displayName : '默认模型'}${levelLabel ? `（思考${levelLabel}）` : ''}，下一轮对话开始生效。`,
    );
  };

  /** 切换思考档位（提示里带上档位名） */
  const pickReasoningLevel = (levelKey: string, label: string) => {
    setReasoningLevel(levelKey);
    setSwitchNotice(
      `已切换到 ${selectedModel ? selectedModel.displayName : '默认模型'}（思考${label}），下一轮对话开始生效。`,
    );
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const text = input.trim();
    if (!text || busy) {
      return;
    }
    setInput('');
    // 本轮已按当前配置发出，切换提示不再需要
    setSwitchNotice(null);
    // modelId / reasoningLevel 随请求体下发（后端 ChatStreamRequest 读取）
    sendMessage(
      { text },
      {
        body: {
          modelId: modelValue === DEFAULT_MODEL_VALUE ? undefined : modelValue,
          reasoningLevel: reasoningLevel || undefined,
        },
      },
    );
  };

  // 流式期间最后一条 assistant 消息显示生成中状态
  const lastAiIndex = busy ? findLastIndex(messages, m => m.role === 'assistant') : -1;

  const modelLabel = selectedModel ? selectedModel.displayName : '默认模型';
  const hasLevels = !!selectedModel && selectedModel.reasoningLevels.length > 0;

  return (
    <div className="chat ai-theme">
      <header className="page-header">
        <div>
          <h1>Solon AI Agent 对话</h1>
          <p className="chat-sub">solon-ai-ui-aisdk（AI SDK 协议 SSE）× @ai-sdk/react useChat</p>
        </div>
        <button className="btn ghost" onClick={onNewChat} disabled={busy}>
          新对话
        </button>
      </header>

      <div className="msg-list" ref={listRef}>
        {messages.length === 0 ? (
          <div className="empty">
            <p>开始与 Agent 对话吧 👋</p>
            <p className="empty-hint">
              多轮上下文由后端 AgentSession 维持，回复逐字流式输出。
            </p>
          </div>
        ) : (
          messages.map((message, index) => (
            <MessageBubble
              key={message.id}
              message={message}
              streaming={index === lastAiIndex}
            />
          ))
        )}
      </div>

      {error ? (
        <div className="error-bar">
          出错了：{(error as Error)?.message ?? String(error)}
        </div>
      ) : null}

      {/* 切换提示：消息区下方灰字（只影响下一轮，发送后自动消失） */}
      {switchNotice ? <div className="chat-switch-notice">{switchNotice}</div> : null}

      <form className="chat-input" onSubmit={submit}>
        <input
          value={input}
          onChange={event => setInput(event.target.value)}
          placeholder={busy ? '生成中…' : '输入消息，回车发送'}
          autoFocus
        />

        {/* 模型 + 思考深度：都在这个弹窗里选，外层按钮直接显示当前模型与档位 */}
        <ModelSelector open={selectorOpen} onOpenChange={setSelectorOpen}>
          <ModelSelectorTrigger asChild>
            {/* type="button"：避免在 form 内触发提交 */}
            <button
              type="button"
              className="model-trigger"
              disabled={busy}
              title={`${modelLabel}${activeLevel ? ` · 思考${activeLevel.label}` : ''}`}
            >
              <CpuIcon className="model-trigger-icon" />
              <span className="model-trigger-label">{modelLabel}</span>
              {hasLevels ? (
                <span className="model-trigger-level">
                  <SparklesIcon className="model-trigger-level-icon" />
                  {activeLevel ? activeLevel.label : '默认'}
                </span>
              ) : null}
              <ChevronsUpDownIcon className="model-trigger-caret" />
            </button>
          </ModelSelectorTrigger>
          {/* 稍加宽：底部「思考深度」一行放得下 4 个档位按钮，不换行 */}
          <ModelSelectorContent title="选择模型" className="sm:max-w-[560px]">
            <ModelSelectorInput placeholder="搜索模型…" />
            <ModelSelectorList>
              <ModelSelectorEmpty>没有匹配的模型</ModelSelectorEmpty>
              <ModelSelectorGroup heading="内置">
                <ModelSelectorItem value="default 默认模型" onSelect={() => pickModel(DEFAULT_MODEL_VALUE, null)}>
                  <CheckIcon
                    className={modelValue === DEFAULT_MODEL_VALUE ? 'model-check' : 'model-check hidden'}
                  />
                  <ModelSelectorName>默认（后台 .env 配置）</ModelSelectorName>
                </ModelSelectorItem>
              </ModelSelectorGroup>
              {groups.map(group => (
                <ModelSelectorGroup key={group.providerId} heading={group.providerName}>
                  {group.models.map(model => {
                    const value = String(model.id);
                    return (
                      <ModelSelectorItem
                        key={value}
                        value={`${model.modelName} ${model.displayName} ${group.providerName}`}
                        onSelect={() => pickModel(value, model)}
                      >
                        <CheckIcon
                          className={modelValue === value ? 'model-check' : 'model-check hidden'}
                        />
                        <ModelSelectorName>{model.displayName}</ModelSelectorName>
                      </ModelSelectorItem>
                    );
                  })}
                </ModelSelectorGroup>
              ))}
              {groups.length === 0 ? (
                <>
                  <ModelSelectorSeparator />
                  <ModelSelectorGroup heading="提示">
                    <ModelSelectorItem value="no-model" disabled>
                      <ModelSelectorName>
                        暂无已配置模型，请到「系统管理 &gt; 模型配置」添加
                      </ModelSelectorName>
                    </ModelSelectorItem>
                  </ModelSelectorGroup>
                </>
              ) : null}
            </ModelSelectorList>

            {/* 思考深度：跟随所选模型的可配置档位，点击即时生效（与模型选择同弹窗，不用二次展开） */}
            <div className="model-popover-reasoning">
              <span className="model-popover-reasoning-label">
                <SparklesIcon className="model-popover-reasoning-icon" />
                思考深度
              </span>
              {hasLevels ? (
                <div className="model-popover-reasoning-options">
                  {selectedModel!.reasoningLevels.map(level => (
                    <Button
                      key={level.key}
                      type="button"
                      variant={reasoningLevel === level.key ? 'secondary' : 'ghost'}
                      size="sm"
                      className={`reasoning-option${
                        reasoningLevel === level.key ? ' is-active' : ''
                      }`}
                      onClick={() => pickReasoningLevel(level.key, level.label)}
                      title={`API 参数值：${level.apiValue || '（不指定）'}`}
                    >
                      {level.label}
                    </Button>
                  ))}
                </div>
              ) : (
                <span className="model-popover-reasoning-empty">
                  {selectedModel ? '该模型未配置思考档位' : '「默认」模型沿用后台 .env 配置'}
                </span>
              )}
            </div>
          </ModelSelectorContent>
        </ModelSelector>

        {busy ? (
          <button type="button" className="btn stop" onClick={() => stop()}>
            停止
          </button>
        ) : (
          <button type="submit" className="btn send" disabled={!input.trim()}>
            发送
          </button>
        )}
      </form>
    </div>
  );
}

function findLastIndex<T>(list: T[], predicate: (item: T) => boolean): number {
  for (let i = list.length - 1; i >= 0; i--) {
    if (predicate(list[i])) {
      return i;
    }
  }
  return -1;
}
