import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronDownIcon, ListChecksIcon, SparklesIcon, WrenchIcon } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from '@/components/ai-elements/conversation';
import {
  Message,
  MessageContent,
  MessageResponse,
} from '@/components/ai-elements/message';
import {
  ChainOfThought,
  ChainOfThoughtContent,
  ChainOfThoughtHeader,
  ChainOfThoughtStep,
} from '@/components/ai-elements/chain-of-thought';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import {
  Context,
  ContextCacheUsage,
  ContextContent,
  ContextContentBody,
  ContextContentFooter,
  ContextContentHeader,
  ContextInputUsage,
  ContextOutputUsage,
  ContextTrigger,
} from '@/components/ai-elements/context';
import type { LanguageModelUsage } from 'ai';
import {
  ToolInput,
  ToolOutput,
} from '@/components/ai-elements/tool';
import {
  Task,
  TaskContent,
  TaskItem,
  TaskTrigger,
} from '@/components/ai-elements/task';
import {
  PromptInput,
  type PromptInputMessage,
  PromptInputSubmit,
  PromptInputTextarea,
} from '@/components/ai-elements/prompt-input';
import { Shimmer } from '@/components/ai-elements/shimmer';
import { cjk } from '@streamdown/cjk';
import { code } from '@streamdown/code';
import { math } from '@streamdown/math';
import { mermaid } from '@streamdown/mermaid';
import { Streamdown } from 'streamdown';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * 出行规划工作台（ReActAgent 工作过程可视化，UI 基于 Vercel AI Elements 组件库）
 *
 * 数据链路（自定义事件协议，与 AI SDK 协议的对话页并存对照）：
 *   fetch 流式读取（POST /api/agent/travel/stream）
 *   → agent-example TravelAgentStreamController（travelAgent + 自定义事件映射）
 *   → 自定义过程事件协议 SSE（plan / text_delta / tool_start / tool_end / metrics / finish…）
 *
 * 组件映射：
 *   任务列表     → Task / TaskTrigger / TaskContent / TaskItem（对话区上方，可折叠）
 *   思考链       → ChainOfThought / ChainOfThoughtHeader / ChainOfThoughtStep
 *                  （一轮对话一个思考链折叠块：深度思考与工具调用均为块内步骤，
 *                    流式锁定展开、整轮结束后 1s 自动折叠，可点击头部回看；
 *                    块内步骤（深度思考文本 / 工具入参与结果）可逐条折叠，点击标签行切换）
 *   工具调用     → ChainOfThoughtStep(WrenchIcon) + ToolInput / ToolOutput（含于思考链块内）
 *   输入/回答    → PromptInput 系列与 Message / MessageContent / MessageResponse
 *                  （AI 文本输出不进思考链，实时流入消息块由 Streamdown 渲染 Markdown）
 *   用量信息     → Context / ContextTrigger / ContextContent 系列（输入框下方：摘要行 +
 *                  悬浮卡展示输入/输出 token、缓存命中率、上下文占用与本轮耗时；
 *                  数据来自运行结束的 metrics 帧 = 框架 RunEndEvent.getMetrics()）
 *
 * 多轮会话：conversationId 随请求上报，后端按其维持 AgentSession（澄清追问续聊）。
 */
const TRAVEL_STREAM_API = '/api/agent/travel/stream';

type PlanStage = 'CREATE' | 'PROGRESS' | 'REVISE' | 'COMPLETE';

interface PlanState {
  stage: PlanStage;
  plans: string[];
  planIndex: number;
}

interface ThinkingStep {
  kind: 'thinking';
  key: number;
  text: string;
  /** 是否仍在流式生成（thinking_end 后置为 false） */
  open: boolean;
}

interface ToolStep {
  kind: 'tool';
  key: number;
  callId: string;
  toolName: string;
  args: unknown;
  status: 'running' | 'done' | 'failed';
  output?: string;
  error?: string;
}

type Step = ThinkingStep | ToolStep;

/**
 * 本轮用量度量（后端 metrics 帧，取自框架 RunEndEvent.getMetrics()）：
 * 一次运行的累计 token 用量 + 缓存命中率 + 上下文占用（末次推理输入 token / 模型上下文窗口）。
 */
interface TurnMetrics {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  cacheReadInputTokens: number;
  cacheCreationInputTokens: number;
  /** 缓存命中率（%）：缓存读取输入 token / 输入 token，后端已保留两位小数 */
  cacheRate: number;
  durationMs: number;
  /** 上下文占用 token（末次推理的输入 token） */
  contextTokens: number;
  /** 模型上下文窗口 token（后端配置 ai.chat.openai.context-length） */
  contextLength: number;
}

interface Turn {
  id: number;
  userText: string;
  status: 'streaming' | 'done';
  plan: PlanState | null;
  steps: Step[];
  answer: string;
  metrics: TurnMetrics | null;
  turnCount?: number;
  error?: string;
}

type StreamEvent = { type: string } & Record<string, unknown>;

/** 步骤 key 序列（同一 turn 内兄弟节点唯一即可） */
let stepSeq = 0;
function nextKey(): number {
  return ++stepSeq;
}

/** 收尾一轮：AI 文本输出已实时流入消息块（turn.answer），此处仅标记结束。 */
function finalizeTurn(turn: Turn): Turn {
  return { ...turn, status: 'done' };
}

/**
 * 本轮是否已有可见回答文本。
 *
 * 模型常在正文之前先吐 "\n\n" 之类的纯空白增量（工具调用轮尤其常见），此时 answer 非空
 * 但 Markdown 渲染不出任何内容，若按 `turn.answer` 判空就会留下一个只有背景与内边距的
 * 空气泡，直到正文到达才被填充；这里按「去掉空白后是否有内容」判断。
 */
function hasVisibleAnswer(turn: Turn): boolean {
  return turn.answer.trim().length > 0;
}

/**
 * 流式读取自定义 SSE：帧以空行分隔，载荷为 data:<json>（终帧 [DONE] 哨兵）。
 */
async function streamTravel(
  body: { id: string; message: string },
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const res = await fetch(TRAVEL_STREAM_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`HTTP ${res.status}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let sep = buffer.indexOf('\n\n');
    while (sep >= 0) {
      const frame = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      sep = buffer.indexOf('\n\n');
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data:')) {
          continue;
        }
        const payload = line.slice(5).trim();
        if (!payload || payload === '[DONE]') {
          continue;
        }
        try {
          onEvent(JSON.parse(payload) as StreamEvent);
        } catch {
          // 坏帧静默跳过（协议帧均为紧凑 JSON，正常不会发生）
        }
      }
    }
  }
}

const PLAN_STAGE_LABEL: Record<PlanStage, string> = {
  CREATE: '已创建',
  PROGRESS: '推进中',
  REVISE: '已修订',
  COMPLETE: '已完成',
};

/**
 * 任务列表面板（Task 组件，固定在聊天输入框上方，可折叠/展开）。
 * 展示最近一次运行产生的计划；新计划创建（CREATE）时自动展开。
 */
function PlanPanel({ plan }: { plan: PlanState }) {
  const [open, setOpen] = useState(true);
  const lastStageRef = useRef<PlanStage | null>(null);

  useEffect(() => {
    if (plan.stage === 'CREATE' && lastStageRef.current !== 'CREATE') {
      setOpen(true);
    }
    lastStageRef.current = plan.stage;
  }, [plan.stage]);

  const doneCount = plan.plans.filter((_, index) => index < plan.planIndex).length;

  return (
    <Task className="plan-panel" open={open} onOpenChange={setOpen}>
      <TaskTrigger title="任务列表" className="px-3 py-2.5">
        <div className="flex w-full items-center gap-2 text-sm">
          <ListChecksIcon className="size-4 text-muted-foreground" />
          <span className="font-medium text-foreground">任务列表</span>
          <Badge
            className={cn(
              'rounded-full text-xs',
              plan.stage === 'COMPLETE' && 'bg-emerald-50 text-emerald-700',
            )}
            variant="secondary"
          >
            {PLAN_STAGE_LABEL[plan.stage] ?? plan.stage}
          </Badge>
          <span className="ml-auto text-muted-foreground text-xs">
            {doneCount}/{plan.plans.length} 已完成
          </span>
          <ChevronDownIcon className="size-4 text-muted-foreground transition-transform group-data-[state=open]:rotate-180" />
        </div>
      </TaskTrigger>
      <TaskContent>
        {plan.plans.map((text, index) => {
          const state =
            index < plan.planIndex ? 'done' : index === plan.planIndex ? 'active' : 'pending';
          return (
            <TaskItem
              key={index}
              className={cn(
                'flex items-start gap-2 text-sm',
                state === 'done' && 'text-muted-foreground line-through',
                state === 'active' && 'font-medium text-foreground',
                state === 'pending' && 'text-muted-foreground',
              )}
            >
              <span
                className={cn(
                  'flex-none',
                  state === 'done' && 'text-emerald-600',
                  state === 'active' && 'animate-pulse text-primary',
                )}
              >
                {state === 'done' ? '✓' : state === 'active' ? '▶' : '○'}
              </span>
              <span className={cn(state === 'active' && 'font-medium')}>{text}</span>
            </TaskItem>
          );
        })}
      </TaskContent>
    </Task>
  );
}

/** 思考链整轮结束后的自动折叠延迟（与 Reasoning 组件的 AUTO_CLOSE_DELAY 一致） */
const AUTO_COLLAPSE_DELAY = 1000;

const thoughtMarkdownPlugins = { cjk, code, math, mermaid };

/**
 * 可折叠的思考链步骤：把 ChainOfThoughtStep 的标签行作为触发器，详情（思考文本 /
 * 工具入参与结果）挂在其下的 CollapsibleContent 中，点击标签行即可展开/收起。
 * 步骤随流式出现时默认展开（defaultOpen），思考链折叠块重新展开回看时按当前
 * streaming 态重算：已完成步骤默认收起成紧凑时间线，仍在生成的步骤默认展开。
 */
function CollapsibleChainStep({
  icon,
  label,
  status,
  className,
  defaultOpen,
  children,
}: {
  icon: LucideIcon;
  label: string;
  status: 'complete' | 'active' | 'pending';
  className?: string;
  defaultOpen: boolean;
  children: ReactNode;
}) {
  return (
    <Collapsible defaultOpen={defaultOpen}>
      <ChainOfThoughtStep
        className={className}
        icon={icon}
        label={
          <CollapsibleTrigger asChild>
            <button
              type="button"
              className="group flex w-full cursor-pointer items-center gap-1.5 text-left transition-colors hover:text-foreground"
            >
              {label}
              <ChevronDownIcon className="size-3.5 text-muted-foreground transition-transform group-data-[state=open]:rotate-180" />
            </button>
          </CollapsibleTrigger>
        }
        status={status}
      >
        <CollapsibleContent className="data-[state=closed]:fade-out-0 data-[state=closed]:slide-out-to-top-1 data-[state=open]:slide-in-from-top-1 outline-none data-[state=closed]:animate-out data-[state=open]:animate-in">
          {children}
        </CollapsibleContent>
      </ChainOfThoughtStep>
    </Collapsible>
  );
}

/** 深度思考步骤（ChainOfThoughtStep + SparklesIcon 星星图标，流式中为 active 态）。 */
function ThinkingChainStep({ step }: { step: ThinkingStep }) {
  return (
    <CollapsibleChainStep
      defaultOpen={step.open}
      icon={SparklesIcon}
      label="深度思考"
      status={step.open ? 'active' : 'complete'}
    >
      {step.text.trim() ? (
        <Streamdown
          className="md text-muted-foreground text-sm [&>*:first-child]:mt-0 [&>*:last-child]:mb-0"
          plugins={thoughtMarkdownPlugins}
        >
          {step.text}
        </Streamdown>
      ) : null}
    </CollapsibleChainStep>
  );
}

/** 工具调用步骤（ChainOfThoughtStep：WrenchIcon + 工具名，运行中为 active 态、失败标红；入参/结果含于步骤内） */
function ToolChainStep({ step }: { step: ToolStep }) {
  return (
    <CollapsibleChainStep
      className={step.status === 'failed' ? 'text-red-600' : undefined}
      defaultOpen={step.status === 'running'}
      icon={WrenchIcon}
      label={step.toolName}
      status={step.status === 'running' ? 'active' : 'complete'}
    >
      {step.args != null ? <ToolInput input={step.args} /> : null}
      {step.output != null || step.error ? (
        <ToolOutput output={step.output ?? ''} errorText={step.error} />
      ) : null}
    </CollapsibleChainStep>
  );
}

/**
 * 一轮对话的思考链（ChainOfThought 组件折叠块）：
 * 该轮全部深度思考与工具调用统一作为块内步骤（带图标、状态与竖向连接线），
 * 流式期间锁定展开，整轮结束后延迟 1s 自动折叠，可点击头部重新展开回看；
 * 块内每个步骤的详情可独立折叠（点击步骤标签行切换）。
 */
function TurnChainView({ turn }: { turn: Turn }) {
  const streaming = turn.status === 'streaming';
  const [open, setOpen] = useState(streaming);
  const everStreamedRef = useRef(streaming);

  // 流式期间锁定展开；整轮结束后延迟自动折叠
  useEffect(() => {
    if (streaming) {
      everStreamedRef.current = true;
      setOpen(true);
      return;
    }
    if (!everStreamedRef.current) {
      return;
    }
    const timer = setTimeout(() => setOpen(false), AUTO_COLLAPSE_DELAY);
    return () => clearTimeout(timer);
  }, [streaming]);

  return (
    <ChainOfThought className="mb-2" open={open} onOpenChange={setOpen}>
      <ChainOfThoughtHeader>
        {streaming ? <Shimmer duration={1}>思考中…</Shimmer> : '思考链'}
      </ChainOfThoughtHeader>
      <ChainOfThoughtContent>
        {turn.steps.map(step => {
          // 无文本且已结束的深度思考段不渲染（流式中尚未出字的保留，作为活动指示）
          if (step.kind === 'thinking' && !step.open && !step.text.trim()) {
            return null;
          }
          return step.kind === 'tool' ? (
            <ToolChainStep key={step.key} step={step} />
          ) : (
            <ThinkingChainStep key={step.key} step={step} />
          );
        })}
      </ChainOfThoughtContent>
    </ChainOfThought>
  );
}

// ------------------------------------------------------------
// 用量信息（对话框下方，AI Elements Context 组件）

const compactFormat = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

/** 紧凑格式化 token 数（12.4K / 1.2M），与 AI Elements Context 的展示口径一致 */
function compactTokens(value: number): string {
  return compactFormat.format(value);
}

/** 用量面板中的一行指标：左侧标签（弱化），右侧数值 + 可选补充说明 */
function UsageRow({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-muted-foreground">{label}</span>
      <span>
        {value}
        {hint ? <span className="ml-2 text-muted-foreground">{hint}</span> : null}
      </span>
    </div>
  );
}

/**
 * 本轮用量条（固定在输入框下方）：
 * 左侧一行摘要（输入 / 输出 / 缓存命中率），右侧为 AI Elements Context 触发器——
 * 悬浮展开上下文占用（比例 + 进度条）、各分项 token 用量与本轮耗时。
 */
function UsageBar({ metrics }: { metrics: TurnMetrics }) {
  // 上下文窗口缺省（配置为 0）时不渲染占用悬浮卡，避免占用率除零
  const hasContext = metrics.contextLength > 0;
  const contextPercent = hasContext ? (metrics.contextTokens / metrics.contextLength) * 100 : 0;

  // AI Elements Context 的 usage 口径（ai 包 LanguageModelUsage）
  const usage: LanguageModelUsage = {
    inputTokens: metrics.promptTokens,
    inputTokenDetails: {
      noCacheTokens: Math.max(metrics.promptTokens - metrics.cacheReadInputTokens, 0),
      cacheReadTokens: metrics.cacheReadInputTokens,
      cacheWriteTokens: metrics.cacheCreationInputTokens,
    },
    outputTokens: metrics.completionTokens,
    outputTokenDetails: {
      textTokens: metrics.completionTokens,
      reasoningTokens: 0,
    },
    totalTokens: metrics.totalTokens,
  };

  return (
    <div className="usage-bar">
      <span className="usage-summary">
        本轮用量：输入 {compactTokens(metrics.promptTokens)} · 输出{' '}
        {compactTokens(metrics.completionTokens)} · 缓存命中 {metrics.cacheRate.toFixed(1)}%
      </span>
      {hasContext ? (
        <Context
          usedTokens={metrics.contextTokens}
          maxTokens={metrics.contextLength}
          usage={usage}
        >
          <ContextTrigger className="usage-context-trigger" />
          <ContextContent>
            <ContextContentHeader />
            <ContextContentBody className="space-y-1.5">
              <ContextInputUsage>
                <UsageRow
                  label="输入"
                  value={compactTokens(metrics.promptTokens)}
                  hint={metrics.cacheReadInputTokens > 0
                    ? `缓存读取 ${compactTokens(metrics.cacheReadInputTokens)}`
                    : undefined}
                />
              </ContextInputUsage>
              <ContextOutputUsage>
                <UsageRow label="输出" value={compactTokens(metrics.completionTokens)} />
              </ContextOutputUsage>
              <ContextCacheUsage>
                <UsageRow
                  label="缓存"
                  value={`命中率 ${metrics.cacheRate.toFixed(1)}%`}
                  hint={compactTokens(metrics.cacheReadInputTokens)}
                />
              </ContextCacheUsage>
              <UsageRow
                label="上下文"
                value={`${compactTokens(metrics.contextTokens)} / ${compactTokens(metrics.contextLength)}`}
                hint={`占用 ${contextPercent.toFixed(1)}%`}
              />
            </ContextContentBody>
            <ContextContentFooter>
              <span className="text-muted-foreground">本轮耗时</span>
              <span>{(metrics.durationMs / 1000).toFixed(1)} s</span>
            </ContextContentFooter>
          </ContextContent>
        </Context>
      ) : null}
    </div>
  );
}

const STORAGE_KEY = 'travel-demo-session';

interface Session {
  conversationId: string;
  turns: Turn[];
}

export default function TravelDemoPage() {
  const [input, setInput] = useState('');
  const [session, setSession] = useState<Session>(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      try {
        const data = JSON.parse(stored) as Session;
        if (data?.conversationId && Array.isArray(data.turns)) {
          return data;
        }
      } catch {
        // ignore
      }
    }
    return { conversationId: crypto.randomUUID(), turns: [] };
  });

  const { conversationId, turns } = session;

  // 写入本地缓存
  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  }, [session]);

  const updateSession = (updater: (prev: Session) => Session) => {
    setSession(updater);
  };

  const abortRef = useRef<AbortController | null>(null);

  const busy = turns.some(turn => turn.status === 'streaming');

  // 组件卸载（切换菜单）时中断进行中的流，后端随之断开并停止 Agent
  useEffect(() => () => abortRef.current?.abort(), []);

  const applyToLast = (fn: (turn: Turn) => Turn) => {
    updateSession(prev => {
      if (prev.turns.length === 0) {
        return prev;
      }
      const turns = prev.turns.slice();
      turns[turns.length - 1] = fn(turns[turns.length - 1]);
      return { ...prev, turns };
    });
  };

  const handleEvent = (event: StreamEvent) => {
    switch (event.type) {
      case 'plan': {
        const stage = String(event.stage) as PlanStage;
        const plans = Array.isArray(event.plans) ? event.plans.map(String) : [];
        const planIndex = typeof event.planIndex === 'number' ? event.planIndex : 0;
        applyToLast(turn => ({ ...turn, plan: { stage, plans, planIndex } }));
        break;
      }
      case 'text_delta': {
        // AI 文本输出（最终回答）：不进思考链，实时流入下方消息块
        const text = String(event.text ?? '');
        if (!text) {
          break;
        }
        applyToLast(turn => ({ ...turn, answer: turn.answer + text }));
        break;
      }
      case 'thinking_start': {
        applyToLast(turn => ({
          ...turn,
          steps: [...turn.steps, { kind: 'thinking', key: nextKey(), text: '', open: true }],
        }));
        break;
      }
      case 'thinking_delta': {
        const text = String(event.text ?? '');
        if (!text) {
          break;
        }
        applyToLast(turn => {
          const steps = turn.steps.slice();
          const last = steps[steps.length - 1];
          if (last && last.kind === 'thinking' && last.open) {
            steps[steps.length - 1] = { ...last, text: last.text + text };
          } else {
            steps.push({ kind: 'thinking', key: nextKey(), text, open: true });
          }
          return { ...turn, steps };
        });
        break;
      }
      case 'thinking_end': {
        applyToLast(turn => ({
          ...turn,
          steps: turn.steps.map((step, index) =>
            index === turn.steps.length - 1 && step.kind === 'thinking' && step.open
              ? { ...step, open: false }
              : step,
          ),
        }));
        break;
      }
      case 'tool_start': {
        applyToLast(turn => ({
          ...turn,
          steps: [
            ...turn.steps,
            {
              kind: 'tool',
              key: nextKey(),
              callId: String(event.callId ?? ''),
              toolName: String(event.toolName ?? 'unknown'),
              args: event.args ?? null,
              status: 'running',
            },
          ],
        }));
        break;
      }
      case 'tool_end': {
        const callId = String(event.callId ?? '');
        const output = event.output == null ? '' : String(event.output);
        const error = event.error == null ? undefined : String(event.error);
        applyToLast(turn => ({
          ...turn,
          steps: turn.steps.map(step =>
            step.kind === 'tool' && step.callId === callId && step.status === 'running'
              ? { ...step, status: error ? 'failed' : 'done', output, error }
              : step,
          ),
        }));
        break;
      }
      case 'error': {
        applyToLast(turn => ({ ...turn, error: String(event.message ?? '未知错误') }));
        break;
      }
      case 'metrics': {
        // 运行结束的度量帧（token 用量 / 缓存命中率 / 上下文占用）
        const num = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) ? value : 0);
        const metrics: TurnMetrics = {
          promptTokens: num(event.promptTokens),
          completionTokens: num(event.completionTokens),
          totalTokens: num(event.totalTokens),
          cacheReadInputTokens: num(event.cacheReadInputTokens),
          cacheCreationInputTokens: num(event.cacheCreationInputTokens),
          cacheRate: num(event.cacheRate),
          durationMs: num(event.durationMs),
          contextTokens: num(event.contextTokens),
          contextLength: num(event.contextLength),
        };
        applyToLast(turn => ({ ...turn, metrics }));
        break;
      }
      case 'finish': {
        // 正常收尾：任务列表视为全部完成。原因：框架的 planIndex 只能由
        // update_plan_progress 推进（RunEnd 无自动完成逻辑），而最后一步的
        // 产出就是最终回答，模型通常不会再补一次 update_plan_progress(N+1)，
        // 导致末步骤永远停在「进行中」。出错/中断的轮次保留原进度。
        applyToLast(turn => {
          const done = finalizeTurn(turn);
          if (!done.error && done.plan && done.plan.planIndex < done.plan.plans.length) {
            const finishedPlan: PlanState = {
              ...done.plan,
              stage: 'COMPLETE',
              planIndex: done.plan.plans.length,
            };
            return { ...done, plan: finishedPlan };
          }
          return done;
        });
        break;
      }
      case 'run_end': {
        const num = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) ? value : 0);
        applyToLast(turn => ({ ...turn, turnCount: num(event.turnCount) }));
        break;
      }
      default:
        // text_start / text_end / reason_start / reason_end /
        // action_start / action_end / start：边界帧不单独渲染，静默忽略
        break;
    }
  };

  const stop = () => {
    abortRef.current?.abort();
  };

  const newSession = () => {
    updateSession(() => ({ conversationId: crypto.randomUUID(), turns: [] }));
  };

  // 最近一次运行产生的任务列表（多轮时展示最新计划，固定在对话区上方）
  let latestPlan: PlanState | null = null;
  for (let i = turns.length - 1; i >= 0; i--) {
    if (turns[i].plan) {
      latestPlan = turns[i].plan;
      break;
    }
  }

  // 最近一轮的用量度量（对话框下方用量条展示；本轮未结束前保留上一轮数据）
  let latestMetrics: TurnMetrics | null = null;
  for (let i = turns.length - 1; i >= 0; i--) {
    if (turns[i].metrics) {
      latestMetrics = turns[i].metrics;
      break;
    }
  }

  const submit = (message: PromptInputMessage) => {
    const text = (message.text ?? '').trim();
    if (!text || busy) {
      return;
    }
    setInput('');
    updateSession(prev => ({
      ...prev,
      turns: [
        ...prev.turns,
        {
          id: Date.now(),
          userText: text,
          status: 'streaming',
          plan: null,
          steps: [],
          answer: '',
          metrics: null,
        },
      ],
    }));

    const controller = new AbortController();
    abortRef.current = controller;
    streamTravel({ id: conversationId, message: text }, handleEvent, controller.signal)
      .catch((err: unknown) => {
        // 用户主动停止：保留已生成的过程与部分结果
        if ((err as Error)?.name !== 'AbortError') {
          updateSession(prev =>
            prev.turns.some(turn => turn.status === 'streaming')
              ? {
                  ...prev,
                  turns: prev.turns.map(turn =>
                    turn.status === 'streaming'
                      ? { ...finalizeTurn(turn), error: turn.error ?? (err as Error)?.message ?? String(err) }
                      : turn,
                  ),
                }
              : prev,
          );
        }
      })
      .finally(() => {
        // 无论正常结束、异常还是中断，都确保该轮不再处于 streaming 态
        updateSession(prev => ({
          ...prev,
          turns: prev.turns.map(turn => (turn.status === 'streaming' ? finalizeTurn(turn) : turn)),
        }));
        if (abortRef.current === controller) {
          abortRef.current = null;
        }
      });
  };

  return (
    <div className="travel ai-theme">
      <header className="page-header">
        <div>
          <h1>出行规划工作台</h1>
          <p className="chat-sub">
            ReActAgent 工作过程可视化 · AI Elements 组件库（自定义事件协议 SSE）
          </p>
        </div>
        <button className="btn ghost" onClick={newSession} disabled={busy}>
          新会话
        </button>
      </header>

      <Conversation className="travel-conversation">
        <ConversationContent className="gap-6 pb-6">
          {turns.length === 0 ? (
            <ConversationEmptyState
              className="text-muted-foreground"
              title="用一句话描述出行需求 🧭"
              description="例如「我明天下午6点前要从北京到上海」。信息不全时 Agent 会先追问，可多轮补充；planningMode 会先拆解任务列表，再依次调用天气 / 路线 / 火车票 / 机票工具。"
            />
          ) : (
            turns.map(turn => (
              <div className="turn" key={turn.id}>
                <Message from="user">
                  <MessageContent>
                    <MessageResponse>{turn.userText}</MessageResponse>
                  </MessageContent>
                </Message>
                {/* 一轮对话一个思考链折叠块（深度思考 + 工具调用），整轮结束后自动折叠；
                    无步骤且尚无可见输出时以 Shimmer 作活动指示 */}
                {turn.steps.length > 0 ? (
                  <div className="trace">
                    <TurnChainView turn={turn} />
                  </div>
                ) : turn.status === 'streaming' && !hasVisibleAnswer(turn) ? (
                  <Shimmer className="text-sm" duration={1.5}>
                    Agent 工作中…
                  </Shimmer>
                ) : null}
                {turn.error ? <div className="error-bar">出错了：{turn.error}</div> : null}
                {hasVisibleAnswer(turn) ? (
                  <Message from="assistant">
                    <MessageContent>
                      <MessageResponse className="md">{turn.answer}</MessageResponse>
                    </MessageContent>
                  </Message>
                ) : null}
              </div>
            ))
          )}
        </ConversationContent>
        <ConversationScrollButton />
      </Conversation>

      {/* 任务列表：固定在聊天输入框上方（可折叠/展开） */}
      {latestPlan ? <PlanPanel plan={latestPlan} /> : null}

      <PromptInput onSubmit={submit} className="travel-prompt">
        <PromptInputTextarea
          value={input}
          onChange={event => setInput(event.target.value)}
          placeholder={busy ? 'Agent 工作中…' : '输入出行需求，或回答 Agent 的追问'}
          rows={1}
          className="min-h-11 pr-12"
        />
        <PromptInputSubmit
          status={busy ? 'streaming' : 'ready'}
          onStop={stop}
          disabled={busy ? undefined : !input.trim()}
          className="absolute bottom-1 right-1"
        />
      </PromptInput>

      {/* 执行统计：对话框下方左侧展示轮次 / 步骤 */}
      {turns.length > 0 ? (
        <div className="travel-status-bar">
          <span>轮次：{turns.length}</span>
          <span>
            步骤：
            {(() => {
              const latest = turns[turns.length - 1];
              return latest.turnCount ?? 0;
            })()}
          </span>
        </div>
      ) : null}

      {/* 用量信息：对话框下方展示最近一轮的 token 用量与上下文占用 */}
      {latestMetrics ? <UsageBar metrics={latestMetrics} /> : null}
    </div>
  );
}
