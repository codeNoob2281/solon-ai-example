/**
 * 模型配置接口封装（「系统管理 > 模型配置」页 + 流式对话页模型选择器共用）
 *
 * 后端：model-example 的 ModelConfigController（/api/model/*）
 * 错误形态与其他 /api 接口一致（ApiError：status + message），页面统一 catch 提示。
 */
import { ApiError, del, getJSON, postJSON, putJSON } from '@/lib/api';

/** 推理档位（apiValue 为下发给供应商的参数值；空串表示不指定，off 表示关闭思考） */
export interface ReasoningLevel {
  key: string;
  label: string;
  apiValue: string;
}

/** 模型配置（后端回显形态） */
export interface ModelConfig {
  id: number;
  providerId: number;
  providerName: string | null;
  modelName: string;
  displayName: string | null;
  /** 上下文窗口（tokens，0 = 未知） */
  contextWindow: number;
  /** 最大输出 tokens（0 = 未知） */
  maxOutputTokens: number;
  inputTypes: string[];
  reasoningLevels: ReasoningLevel[];
  status: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 供应商（含模型列表） */
export interface ProviderConfig {
  id: number;
  name: string;
  type: string;
  baseUrl: string;
  hasApiKey: boolean;
  apiKeyHint: string | null;
  status: number;
  remark: string | null;
  modelCount: number;
  createdAt: string | null;
  updatedAt: string | null;
  models: ModelConfig[];
}

/** 模型保存请求（窗口字段为字符串，支持 1M / 200K 简写；空 = 自动获取） */
export interface ModelSaveRequest {
  id?: number;
  modelName: string;
  displayName?: string;
  contextWindow?: string;
  maxOutputTokens?: string;
  inputTypes?: string[];
  reasoningLevels?: ReasoningLevel[];
  status?: number;
}

/** 供应商保存请求（models=undefined 不改模型，[]=清空） */
export interface ProviderSaveRequest {
  name: string;
  type?: string;
  baseUrl: string;
  apiKey?: string;
  status?: number;
  remark?: string;
  models?: ModelSaveRequest[];
}

/** 供应商类型预设 */
export interface ProviderTypeOption {
  value: string;
  label: string;
  defaultBaseUrl: string;
}

/** 连通性测试结果 */
export interface ProviderTestResult {
  success: boolean;
  message: string;
  elapsedMs: number;
  modelCount: number;
}

/** 单模型可用性测试结果 */
export interface ModelTestResult {
  modelId: number;
  modelName: string | null;
  displayName: string | null;
  success: boolean;
  message: string;
  elapsedMs: number;
  reply: string | null;
}

/** 远程模型信息（含窗口信息补全） */
export interface RemoteModelInfo {
  id: string;
  ownedBy: string | null;
  contextWindow: number | null;
  maxOutputTokens: number | null;
  inputTypes: string[] | null;
}

/** 聊天页模型选项（按供应商分组） */
export interface ChatModelOption {
  id: number;
  modelName: string;
  displayName: string;
  contextWindow: number;
  maxOutputTokens: number;
  inputTypes: string[];
  reasoningLevels: ReasoningLevel[];
}

export interface ChatModelGroup {
  providerId: number;
  providerName: string;
  providerType: string;
  models: ChatModelOption[];
}

// ------------------------------------------------------------
// API

export function listProviders(): Promise<ProviderConfig[]> {
  return getJSON<ProviderConfig[]>('/api/model/providers');
}

export function getProvider(id: number): Promise<ProviderConfig> {
  return getJSON<ProviderConfig>(`/api/model/providers/${id}`);
}

export function createProvider(request: ProviderSaveRequest): Promise<ProviderConfig> {
  return postJSON<ProviderConfig>('/api/model/providers', request);
}

export function updateProvider(id: number, request: ProviderSaveRequest): Promise<ProviderConfig> {
  return putJSON<ProviderConfig>(`/api/model/providers/${id}`, request);
}

export function deleteProvider(id: number): Promise<unknown> {
  return del<unknown>(`/api/model/providers/${id}`);
}

export function testProvider(id: number): Promise<ProviderTestResult> {
  return postJSON<ProviderTestResult>(`/api/model/providers/${id}/test`);
}

export function listRemoteModels(id: number): Promise<RemoteModelInfo[]> {
  return getJSON<RemoteModelInfo[]>(`/api/model/providers/${id}/remote-models`);
}

/** 按草稿参数拉取远程模型（新供应商未保存时也能「一键获取」；apiKey 留空则沿用已保存值） */
export function listRemoteModelsByDraft(request: {
  providerId?: number;
  type?: string;
  baseUrl: string;
  apiKey?: string;
}): Promise<RemoteModelInfo[]> {
  return postJSON<RemoteModelInfo[]>('/api/model/remote-models', request);
}

export function enrichModels(id: number): Promise<ProviderConfig> {
  return postJSON<ProviderConfig>(`/api/model/providers/${id}/enrich`);
}

/** 测试单个模型可用性（后端用固定提示词「请回复pong」发一次最小对话） */
export function testModel(modelId: number): Promise<ModelTestResult> {
  return postJSON<ModelTestResult>(`/api/model/models/${modelId}/test`);
}

/** 批量测试模型可用性（逐条测试，单条失败不影响其余） */
export function testModels(modelIds: number[]): Promise<ModelTestResult[]> {
  return postJSON<ModelTestResult[]>('/api/model/models/test', modelIds);
}

export function listProviderTypes(): Promise<ProviderTypeOption[]> {
  return getJSON<ProviderTypeOption[]>('/api/model/types');
}

export function listDefaultReasoningLevels(): Promise<ReasoningLevel[]> {
  return getJSON<ReasoningLevel[]>('/api/model/reasoning-levels/default');
}

export function listChatModelGroups(): Promise<ChatModelGroup[]> {
  return getJSON<ChatModelGroup[]>('/api/model/chat-options');
}

export function getModelMetadata(modelName: string, type?: string): Promise<RemoteModelInfo> {
  const params = new URLSearchParams({ modelName });
  if (type) {
    params.set('type', type);
  }
  return getJSON<RemoteModelInfo>(`/api/model/metadata?${params.toString()}`);
}

/** 保存成功统一提示用（避免各页面重复拼文案） */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}
