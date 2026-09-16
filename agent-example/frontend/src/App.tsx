import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport, UIMessage } from 'ai';

/**
 * Solon AI Agent 流式对话页
 *
 * 数据链路：
 *   useChat（DefaultChatTransport，POST /api/agent/chat/stream）
 *   → agent-example AgentChatController（ReActAgent + AiSdkStreamWrapper）
 *   → AI SDK UI Message Stream Protocol v1 SSE（start/text-delta/finish…）
 *   → 前端逐字渲染
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

function findLastIndex<T>(list: T[], predicate: (item: T) => boolean): number {
  for (let i = list.length - 1; i >= 0; i--) {
    if (predicate(list[i])) {
      return i;
    }
  }
  return -1;
}

function MessageBubble({ message, streaming }: { message: UIMessage; streaming: boolean }) {
  const isUser = message.role === 'user';
  const hasText = message.parts.some(part => part.type === 'text' && part.text);

  return (
    <div className={`msg ${isUser ? 'msg-user' : 'msg-ai'}`}>
      <div className="msg-avatar">{isUser ? '你' : 'AI'}</div>
      <div className="msg-bubble">
        {message.parts.map((part, index) => {
          if (part.type === 'text' && part.text) {
            return (
              <div key={index} className="msg-text">
                {part.text}
                {streaming && !isUser ? <span className="caret" /> : null}
              </div>
            );
          }
          if (part.type === 'reasoning') {
            const text = reasoningTextOf(part);
            if (!text) {
              return null;
            }
            return (
              <details key={index} className="msg-reasoning">
                <summary>思考过程</summary>
                <div>{text}</div>
              </details>
            );
          }
          // 其余分片（step-start / data-agent-event / 工具分片等）当前页暂不渲染
          return null;
        })}
        {streaming && !isUser && !hasText ? (
          <span className="pending-text">正在思考…</span>
        ) : null}
      </div>
    </div>
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

  // 流式期间最后一条 assistant 消息显示打字光标
  const lastAiIndex = busy ? findLastIndex(messages, m => m.role === 'assistant') : -1;

  return (
    <div className="chat">
      <header className="chat-header">
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

export default function App() {
  // 每个会话一个 chatId；key 变化触发 ChatPanel 重挂载，即「新对话」
  const [chatId, setChatId] = useState(() => crypto.randomUUID());
  return (
    <ChatPanel
      key={chatId}
      chatId={chatId}
      onNewChat={() => setChatId(crypto.randomUUID())}
    />
  );
}
