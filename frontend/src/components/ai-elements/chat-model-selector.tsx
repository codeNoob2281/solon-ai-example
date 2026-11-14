import { useEffect, useMemo, useState } from 'react';
import { CheckIcon, ChevronsUpDownIcon, CpuIcon, SparklesIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
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
import { listChatModelGroups } from '@/lib/modelApi';
import type { ChatModelGroup, ChatModelOption, ReasoningLevel } from '@/lib/modelApi';

/**
 * 聊天模型选择器（流式对话 / 出行规划工作台 / RAG 问答三页共用）
 *
 * 三页的模型切换语义一致，这里抽成「hook 管状态 + 组件渲染弹窗」：
 *   - {@link useChatModelSelection}：加载按供应商分组的模型列表，维护当前模型与思考档位，
 *     并随请求体组装 modelId / reasoningLevel（「默认」不下发 modelId，走后台 .env）。
 *   - {@link ChatModelSelector}：输入框左侧的触发按钮 + 弹窗（模型按供应商分组 + 底部思考深度分段按钮）。
 *
 * 切换只影响「下一轮」：改动后由页面在消息区下方展示灰色提示，发送时清除。
 */

/** 「默认」模型选项的哨兵 id（不下发 modelId，后端走 .env） */
export const DEFAULT_MODEL_VALUE = 'default';

/** 模型 / 思考档位选择状态 */
export interface ChatModelSelection {
  groups: ChatModelGroup[];
  /** 当前选中项：'default' 或模型配置 id 字符串 */
  modelValue: string;
  /** 当前思考档位 key（空表示不指定） */
  reasoningLevel: string;
  /** 当前选中模型（「默认」为 null） */
  selectedModel: ChatModelOption | null;
  /** 当前生效的推理档位（外层按钮与弹窗内高亮共用） */
  activeLevel: ReasoningLevel | null;
  /** 触发按钮展示的模型名 */
  modelLabel: string;
  /** 所选模型是否配置了思考档位 */
  hasLevels: boolean;
  /** 切换模型 / 档位后的提示文案（发送一轮后应清除） */
  switchNotice: string | null;
  /** 切换模型：推理档位回落到该模型的第一个档位（无档位则清空） */
  pickModel: (value: string, model: ChatModelOption | null) => void;
  /** 切换思考档位 */
  pickReasoningLevel: (levelKey: string, label: string) => void;
  /** 清除切换提示（发送一轮时调用） */
  clearSwitchNotice: () => void;
  /** 组装随请求体下发的模型参数（「默认」不下发 modelId） */
  toRequestBody: () => { modelId?: string; reasoningLevel?: string };
}

/**
 * 模型选择状态 hook：加载分组模型列表 + 维护选择 + 组装请求参数。
 * 配置服务不可用时静默降级为「仅默认模型」，不影响对话本身。
 */
export function useChatModelSelection(): ChatModelSelection {
  const [groups, setGroups] = useState<ChatModelGroup[]>([]);
  const [modelValue, setModelValue] = useState<string>(DEFAULT_MODEL_VALUE);
  const [reasoningLevel, setReasoningLevel] = useState<string>('');
  const [switchNotice, setSwitchNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    listChatModelGroups()
      .then(data => {
        if (!cancelled) {
          setGroups(data);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setGroups([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

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

  const activeLevel = useMemo(() => {
    if (!selectedModel) {
      return null;
    }
    return selectedModel.reasoningLevels.find(level => level.key === reasoningLevel) ?? null;
  }, [selectedModel, reasoningLevel]);

  const pickModel = (value: string, model: ChatModelOption | null) => {
    setModelValue(value);
    const nextLevel = model && model.reasoningLevels.length > 0 ? model.reasoningLevels[0].key : '';
    setReasoningLevel(nextLevel);
    const levelLabel = model?.reasoningLevels.find(level => level.key === nextLevel)?.label;
    setSwitchNotice(
      `已切换到 ${model ? model.displayName : '默认模型'}${levelLabel ? `（思考${levelLabel}）` : ''}，下一轮对话开始生效。`,
    );
  };

  const pickReasoningLevel = (levelKey: string, label: string) => {
    setReasoningLevel(levelKey);
    setSwitchNotice(
      `已切换到 ${selectedModel ? selectedModel.displayName : '默认模型'}（思考${label}），下一轮对话开始生效。`,
    );
  };

  return {
    groups,
    modelValue,
    reasoningLevel,
    selectedModel,
    activeLevel,
    modelLabel: selectedModel ? selectedModel.displayName : '默认模型',
    hasLevels: !!selectedModel && selectedModel.reasoningLevels.length > 0,
    switchNotice,
    pickModel,
    pickReasoningLevel,
    clearSwitchNotice: () => setSwitchNotice(null),
    toRequestBody: () => ({
      modelId: modelValue === DEFAULT_MODEL_VALUE ? undefined : modelValue,
      reasoningLevel: reasoningLevel || undefined,
    }),
  };
}

/**
 * 模型选择触发器 + 弹窗：外层按钮直接显示「模型名 + 当前思考档位」，
 * 弹窗内按供应商分组选择模型，底部为思考深度分段按钮（跟随所选模型的档位）。
 *
 * @param variant default：带描边/底色的独立按钮（对话页、RAG 页输入行左侧）；
 *                ghost：去掉背景与描边的紧凑样式（出行页输入框第二行右下角）
 */
export function ChatModelSelector({
  state,
  disabled,
  variant = 'default',
}: {
  state: ChatModelSelection;
  disabled?: boolean;
  variant?: 'default' | 'ghost';
}) {
  const [open, setOpen] = useState(false);
  const {
    groups,
    modelValue,
    reasoningLevel,
    selectedModel,
    activeLevel,
    modelLabel,
    hasLevels,
    pickModel,
    pickReasoningLevel,
  } = state;

  return (
    <ModelSelector open={open} onOpenChange={setOpen}>
      <ModelSelectorTrigger asChild>
        {/* type="button"：避免在 form 内触发提交 */}
        <button
          type="button"
          className={`model-trigger${variant === 'ghost' ? ' model-trigger--ghost' : ''}`}
          disabled={disabled}
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
            <ModelSelectorItem
              value="default 默认模型"
              onSelect={() => pickModel(DEFAULT_MODEL_VALUE, null)}
            >
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
                  className={`reasoning-option${reasoningLevel === level.key ? ' is-active' : ''}`}
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
  );
}
