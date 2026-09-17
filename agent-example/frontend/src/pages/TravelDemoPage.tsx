import { useEffect, useRef, useState } from 'react';
import { ChevronDownIcon, ListChecksIcon } from 'lucide-react';
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
  Reasoning,
  ReasoningContent,
  ReasoningTrigger,
} from '@/components/ai-elements/reasoning';
import {
  Tool,
  ToolContent,
  ToolHeader,
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
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * 出行规划工作台（ReActAgent 工作过程可视化，UI 基于 Vercel AI Elements 组件库）
 *
 * 数据链路（自定义事件协议，与 AI SDK 协议的对话页并存对照）：
 *   fetch 流式读取（POST /api/agent/travel/stream）
 *   → agent-example TravelAgentStreamController（travelAgent + 自定义事件映射）
 *   → 自定义过程事件协议 SSE（plan / text_delta / tool_start / tool_end / finish…）
 *
 * 组件映射：
 *   任务列表     → Task / TaskTrigger / TaskContent / TaskItem（对话区上方，可折叠）
 *   思考链       → Reasoning / ReasoningTrigger / ReasoningContent（流式自动展开、结束自动收起）
 *   工具调用     → Tool / ToolHeader / ToolContent / ToolInput / ToolOutput（state 徽标）
 *   输入/回答    → PromptInput 系列与 Message / MessageContent / MessageResponse（Streamdown 渲染 Markdown）
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

interface ThoughtStep {
  kind: 'thought';
  key: number;
  text: string;
  /** 是否仍在流式生成（text_end 后置为 false） */
  open: boolean;
}

interface ThinkingStep {
  kind: 'thinking';
  key: number;
  text: string;
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

type Step = ThoughtStep | ThinkingStep | ToolStep;

interface Turn {
  id: number;
  userText: string;
  status: 'streaming' | 'done';
  plan: PlanState | null;
  steps: Step[];
  answer: string;
  error?: string;
}

type StreamEvent = { type: string } & Record<string, unknown>;

/** 步骤 key 序列（同一 turn 内兄弟节点唯一即可） */
let stepSeq = 0;
function nextKey(): number {
  return ++stepSeq;
}

/**
 * 收尾一轮：把最后一段思考文本提升为最终回答（工作过程时间线只留过程）；
 * 出错的轮次保留现场不提升。
 */
function finalizeTurn(turn: Turn): Turn {
  if (turn.error || turn.answer) {
    return { ...turn, status: 'done' };
  }
  const steps = turn.steps.slice();
  const last = steps[steps.length - 1];
  if (last && last.kind === 'thought' && last.text.trim()) {
    return { ...turn, steps: steps.slice(0, -1), answer: last.text, status: 'done' };
  }
  return { ...turn, status: 'done' };
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

/** 工具步骤 → AI SDK ToolUIPart 状态（ToolHeader 徽标依据） */
function toolStateOf(step: ToolStep): 'input-available' | 'output-available' | 'output-error' {
  if (step.status === 'failed') {
    return 'output-error';
  }
  return step.status === 'done' ? 'output-available' : 'input-available';
}

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

/** 思考链 / 深度思考步骤（Reasoning：流式自动展开，结束自动收起） */
function ThoughtView({ step }: { step: ThoughtStep | ThinkingStep }) {
  const isThinking = step.kind === 'thinking';
  return (
    <Reasoning isStreaming={step.open} className="mb-2">
      <ReasoningTrigger
        getThinkingMessage={streaming =>
          streaming ? (
            <Shimmer duration={1}>{isThinking ? '深度思考中…' : '思考中…'}</Shimmer>
          ) : (
            <p>{isThinking ? '深度思考' : '思考'}</p>
          )
        }
      />
      <ReasoningContent className="md">{step.text}</ReasoningContent>
    </Reasoning>
  );
}

/** 工具调用步骤（Tool：状态徽标 + 入参 JSON + 结果/错误） */
function ToolView({ step }: { step: ToolStep }) {
  return (
    <Tool defaultOpen={step.status === 'running'} className="mb-2">
      <ToolHeader type={`tool-${step.toolName}`} state={toolStateOf(step)} />
      <ToolContent>
        {step.args != null ? <ToolInput input={step.args} /> : null}
        {step.output != null || step.error ? (
          <ToolOutput output={step.output ?? ''} errorText={step.error} />
        ) : null}
      </ToolContent>
    </Tool>
  );
}

export default function TravelDemoPage() {
  const [input, setInput] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState(() => crypto.randomUUID());
  const abortRef = useRef<AbortController | null>(null);

  const busy = turns.some(turn => turn.status === 'streaming');

  // 组件卸载（切换菜单）时中断进行中的流，后端随之断开并停止 Agent
  useEffect(() => () => abortRef.current?.abort(), []);

  const applyToLast = (fn: (turn: Turn) => Turn) => {
    setTurns(prev => {
      if (prev.length === 0) {
        return prev;
      }
      const copy = prev.slice();
      copy[copy.length - 1] = fn(copy[copy.length - 1]);
      return copy;
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
      case 'text_start': {
        applyToLast(turn => ({
          ...turn,
          steps: [...turn.steps, { kind: 'thought', key: nextKey(), text: '', open: true }],
        }));
        break;
      }
      case 'text_delta': {
        const text = String(event.text ?? '');
        if (!text) {
          break;
        }
        applyToLast(turn => {
          const steps = turn.steps.slice();
          const last = steps[steps.length - 1];
          if (last && last.kind === 'thought' && last.open) {
            steps[steps.length - 1] = { ...last, text: last.text + text };
          } else {
            // 兜底：异常路径可能没有 text_start 直接来 delta
            steps.push({ kind: 'thought', key: nextKey(), text, open: true });
          }
          return { ...turn, steps };
        });
        break;
      }
      case 'text_end': {
        applyToLast(turn => ({
          ...turn,
          steps: turn.steps.map((step, index) =>
            index === turn.steps.length - 1 && step.kind === 'thought' && step.open
              ? { ...step, open: false }
              : step,
          ),
        }));
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
      default:
        // run_start / run_end / reason_start / reason_end / action_start / action_end / start：
        // 阶段边界帧当前页不单独渲染，静默忽略
        break;
    }
  };

  const stop = () => {
    abortRef.current?.abort();
  };

  const newSession = () => {
    setTurns([]);
    setConversationId(crypto.randomUUID());
  };

  // 最近一次运行产生的任务列表（多轮时展示最新计划，固定在对话区上方）
  let latestPlan: PlanState | null = null;
  for (let i = turns.length - 1; i >= 0; i--) {
    if (turns[i].plan) {
      latestPlan = turns[i].plan;
      break;
    }
  }

  const submit = (message: PromptInputMessage) => {
    const text = (message.text ?? '').trim();
    if (!text || busy) {
      return;
    }
    setInput('');
    setTurns(prev => [
      ...prev,
      { id: Date.now(), userText: text, status: 'streaming', plan: null, steps: [], answer: '' },
    ]);

    const controller = new AbortController();
    abortRef.current = controller;
    streamTravel({ id: conversationId, message: text }, handleEvent, controller.signal)
      .catch((err: unknown) => {
        // 用户主动停止：保留已生成的过程与部分结果
        if ((err as Error)?.name !== 'AbortError') {
          applyToLast(turn =>
            turn.status === 'streaming'
              ? { ...finalizeTurn(turn), error: turn.error ?? (err as Error)?.message ?? String(err) }
              : turn,
          );
        }
      })
      .finally(() => {
        // 无论正常结束、异常还是中断，都确保该轮不再处于 streaming 态
        applyToLast(turn => (turn.status === 'streaming' ? finalizeTurn(turn) : turn));
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
                <div className="trace">
                  {turn.steps.map(step => {
                    if (step.kind === 'tool') {
                      return <ToolView key={step.key} step={step} />;
                    }
                    // 无文本的思考段不渲染：推理型模型中间轮次的可见文本常为空
                    // （思考走 thinking 通道，text 只有 start/end 没有 delta），
                    // 流式中尚未出字的保留（作为活动指示），已结束且为空的隐藏
                    if (!step.open && !step.text.trim()) {
                      return null;
                    }
                    return <ThoughtView key={step.key} step={step} />;
                  })}
                  {turn.status === 'streaming' && turn.steps.length === 0 ? (
                    <Shimmer className="text-sm" duration={1.5}>
                      Agent 工作中…
                    </Shimmer>
                  ) : null}
                  {turn.error ? <div className="error-bar">出错了：{turn.error}</div> : null}
                  {turn.answer ? (
                    <Message from="assistant">
                      <MessageContent>
                        <MessageResponse className="md">{turn.answer}</MessageResponse>
                      </MessageContent>
                    </Message>
                  ) : null}
                </div>
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
    </div>
  );
}
