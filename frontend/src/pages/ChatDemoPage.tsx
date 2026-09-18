import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport, UIMessage } from 'ai';
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

/**
 * Solon AI Agent 流式对话页（纯对话 demo，AI SDK 协议）
 *
 * 数据链路：
 *   useChat（DefaultChatTransport，POST /api/agent/chat/stream）
 *   → agent-example AgentChatController（ReActAgent + AiSdkStreamWrapper）
 *   → AI SDK UI Message Stream Protocol v1 SSE（start/text-delta/finish…）
 *   → 前端逐字渲染
 *
 * 组件映射：
 *   消息 → Message / MessageContent / MessageResponse（Streamdown 渲染 Markdown）
 *   思考 → Reasoning / ReasoningTrigger / ReasoningContent
 *          （流式自动展开、结束后 1s 自动收起，历史消息默认收起可回看）
 *
 * 多轮会话：chatId 由前端生成，useChat({ id }) 随请求体上报，
 * 后端按 chatId 维持 AgentSession 注入对话历史。
 */
const STREAM_API = '/api/agent/chat/stream';

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

  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const text = input.trim();
    if (!text || busy) {
      return;
    }
    setInput('');
    sendMessage({ text });
  };

  // 流式期间最后一条 assistant 消息显示生成中状态
  const lastAiIndex = busy ? findLastIndex(messages, m => m.role === 'assistant') : -1;

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

      <form className="chat-input" onSubmit={submit}>
        <input
          value={input}
          onChange={event => setInput(event.target.value)}
          placeholder={busy ? '生成中…' : '输入消息，回车发送'}
          autoFocus
        />
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
