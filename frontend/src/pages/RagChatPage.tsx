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
 * RAG 问答页（父子分段检索增强，AI SDK 协议）
 *
 * 数据链路：
 *   useChat（DefaultChatTransport，POST /api/rag/chat/stream）
 *   → rag-example RagChatController（检索 → 来源解析 → ChatModel 流式生成 + AiSdkStreamWrapper）
 *   → AI SDK UI Message Stream Protocol v1 SSE（start → metadata(sources) → reasoning/text → finish）
 *   → 前端逐字渲染 + 引用来源 chip
 *
 * 组件映射（复用对话页骨架）：
 *   正文 → MessageResponse（Streamdown 渲染 Markdown）
 *   思考 → Reasoning / ReasoningTrigger / ReasoningContent
 *   来源 → message.metadata.sources（检索在流开始前完成，随 metadata part 一次下发；
 *          chip 纯展示、不可点击，title 作原生 tooltip）
 *
 * 会话语义：无后端会话/记忆，每问独立检索；前端消息历史仅作 UI 展示。
 */
const STREAM_API = '/api/rag/chat/stream';

/** 引用来源（后端 KbSourceItem） */
interface RagSource {
  documentId: string;
  label: string;
  title?: string | null;
}

/** 提取 reasoning 分片文本（协议 v1：reasoning part 用 text 字段承载） */
function reasoningTextOf(part: UIMessage['parts'][number]): string {
  if (part.type !== 'reasoning') {
    return '';
  }
  const text = (part as { text?: unknown }).text;
  return typeof text === 'string' ? text : '';
}

/** 提取消息的引用来源列表（随 metadata part 下发） */
function sourcesOf(message: UIMessage): RagSource[] {
  const metadata = message.metadata as { sources?: RagSource[] } | undefined;
  return Array.isArray(metadata?.sources) ? metadata!.sources : [];
}

function SourceChips({ sources }: { sources: RagSource[] }) {
  if (sources.length === 0) {
    return null;
  }
  return (
    <div className="rag-sources">
      <span className="rag-sources-label">引用来源</span>
      <div className="rag-source-chips">
        {sources.map(source => (
          <span
            key={source.documentId}
            className="rag-source-chip"
            title={source.title || source.label}
          >
            📎 {source.label}
          </span>
        ))}
      </div>
    </div>
  );
}

function MessageBubble({ message, streaming }: { message: UIMessage; streaming: boolean }) {
  const hasVisible = message.parts.some(
    part =>
      (part.type === 'text' && part.text) ||
      (part.type === 'reasoning' && reasoningTextOf(part)),
  );
  const sources = sourcesOf(message);

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
          return null;
        })}
        {streaming && !hasVisible ? (
          <Shimmer className="text-sm" duration={1.5}>
            正在思考…
          </Shimmer>
        ) : null}
        {message.role === 'assistant' ? <SourceChips sources={sources} /> : null}
      </MessageContent>
    </Message>
  );
}

export default function RagChatPage() {
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
          <h1>RAG 问答</h1>
          <p className="chat-sub">父子分段检索增强 × AI SDK 协议 SSE × 引用来源</p>
        </div>
        <button className="btn ghost" onClick={onNewChat} disabled={busy}>
          新对话
        </button>
      </header>

      <div className="msg-list" ref={listRef}>
        {messages.length === 0 ? (
          <div className="empty">
            <p>向知识库提问吧 👋</p>
            <p className="empty-hint">
              每问独立检索知识库（父子分段：子块召回 → 父块进上下文），回答附带引用来源。
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
          placeholder={busy ? '生成中…' : '输入问题，回车发送'}
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
