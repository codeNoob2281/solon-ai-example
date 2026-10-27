import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  ConfigProvider,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popover,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ApiOutlined,
  CloudDownloadOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  createProvider,
  deleteProvider,
  describeError,
  enrichModels,
  getModelMetadata,
  listDefaultReasoningLevels,
  listProviders,
  listProviderTypes,
  listRemoteModels,
  listRemoteModelsByDraft,
  testModel,
  testModels,
  testProvider,
  updateProvider,
} from '@/lib/modelApi';
import type {
  ModelConfig,
  ModelTestResult,
  ProviderConfig,
  ProviderSaveRequest,
  ProviderTypeOption,
  ReasoningLevel,
  RemoteModelInfo,
} from '@/lib/modelApi';

/**
 * 模型配置页（系统管理 > 模型配置）
 *
 * 结构：左侧/上方是供应商列表（新增/编辑/删除/测试），编辑抽屉里维护该供应商下的模型。
 * - 供应商：名称 / 类型 / Base URL / API Key / 状态 / 备注；删除级联删除全部模型；
 * - 测试：只测供应商连通性（GET {baseUrl}/models），不校验具体模型；
 * - 模型：名称 / 显示名 / 上下文窗口 / 最大输出 tokens（均支持 1M、200K 简写，留空自动获取）/
 *   输入类型（文本、图片）/ 预设推理档位（默认 off/low/medium/high，可增删档位并覆盖 API 参数值）；
 * - 一键获取模型列表：拉供应商 /models，按需勾选填入，窗口信息缺失时后端回落 models.dev 目录。
 *
 * 数据获取：lib/api.ts + lib/modelApi.ts 的 fetch 薄封装，页面内 useState/useEffect（不引入 react-query）。
 */

/** antd 主题：与站点其它页面保持一致（主色 / 圆角） */
const MODEL_PAGE_THEME = {
  token: {
    colorPrimary: '#4f6ef7',
    borderRadius: 10,
    fontFamily: 'inherit',
  },
};

const INPUT_TYPE_OPTIONS = [
  { label: '文本', value: 'text' },
  { label: '图片', value: 'image' },
];

/**
 * API Key 输入框（替代 antd 的 Input.Password）
 *
 * 背景：type="password" 会被浏览器当成登录口令，触发密码自动填充、弹出「是否保存密码」，
 * 甚至误填成用户已保存的站点密码。因此这里始终用 type="text"（浏览器不再当凭据框），
 * 遮罩改由 CSS 的 `-webkit-text-security: disc` 实现——输入过程中也显示圆点，只有点「眼睛」才显示明文；
 * 不支持该属性的浏览器（Firefox）退化为 type="password" + autocomplete="new-password"。
 */
function SecretInput({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  const [revealed, setRevealed] = useState(false);
  // Blink/WebKit 支持 -webkit-text-security（圆点遮罩且保留真实输入）；Firefox 不支持
  const supportsTextSecurity =
    typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
      ? CSS.supports('-webkit-text-security', 'disc')
      : false;
  const maskedByCss = supportsTextSecurity && !revealed;

  return (
    <Input
      className={maskedByCss ? 'secret-input' : undefined}
      // 兜底：不支持 CSS 遮罩时用 password 类型（仍带 new-password 抑制自动填充）
      type={supportsTextSecurity ? 'text' : revealed ? 'text' : 'password'}
      value={value}
      autoComplete={supportsTextSecurity ? 'off' : 'new-password'}
      spellCheck={false}
      // 阻止密码管理器（1Password / LastPass）识别为凭据输入框
      data-1p-ignore="true"
      data-lpignore="true"
      data-form-type="other"
      placeholder={placeholder}
      onChange={event => onChange(event.target.value)}
      suffix={
        // 图标反映「当前状态」（与 antd Input.Password 一致）：遮罩中显示闭眼、明文时显示睁眼
        <Button
          type="text"
          size="small"
          tabIndex={-1}
          aria-label={revealed ? '隐藏' : '显示'}
          title={revealed ? '隐藏' : '显示'}
          icon={revealed ? <EyeOutlined /> : <EyeInvisibleOutlined />}
          onClick={() => setRevealed(current => !current)}
        />
      }
    />
  );
}

/** 模型编辑草稿（窗口字段用字符串，提交时原样交给后端解析简写） */
interface ModelDraft {
  /** 前端本地唯一 key（Table rowKey） */
  key: string;
  id?: number;
  modelName: string;
  displayName: string;
  contextWindow: string;
  maxOutputTokens: string;
  inputTypes: string[];
  reasoningLevels: ReasoningLevel[];
  status: number;
}

/** 供应商编辑草稿 */
interface ProviderDraft {
  id?: number;
  name: string;
  type: string;
  baseUrl: string;
  apiKey: string;
  status: number;
  remark: string;
  models: ModelDraft[];
}

let draftSeq = 0;
function nextDraftKey(): string {
  draftSeq += 1;
  return `draft-${draftSeq}`;
}

/** token 数格式化（1024 进制简写，与后端口径一致） */
function formatTokens(tokens: number | null | undefined): string {
  if (!tokens || tokens <= 0) {
    return '未知';
  }
  const K = 1024;
  const M = K * K;
  if (tokens >= M && tokens % M === 0) {
    return `${tokens / M}M`;
  }
  if (tokens >= K && tokens % K === 0) {
    return `${tokens / K}K`;
  }
  return String(tokens);
}

function emptyDraft(): ProviderDraft {
  return {
    name: '',
    type: 'openai',
    baseUrl: '',
    apiKey: '',
    status: 1,
    remark: '',
    models: [],
  };
}

function toModelDraft(model: ModelConfig): ModelDraft {
  return {
    key: `model-${model.id}`,
    id: model.id,
    modelName: model.modelName,
    displayName: model.displayName ?? '',
    contextWindow: model.contextWindow > 0 ? String(model.contextWindow) : '',
    maxOutputTokens: model.maxOutputTokens > 0 ? String(model.maxOutputTokens) : '',
    inputTypes: model.inputTypes.length > 0 ? model.inputTypes : ['text'],
    reasoningLevels: model.reasoningLevels,
    status: model.status,
  };
}

function toProviderDraft(provider: ProviderConfig): ProviderDraft {
  return {
    id: provider.id,
    name: provider.name,
    type: provider.type,
    baseUrl: provider.baseUrl,
    // 编辑时留空表示不修改 API Key
    apiKey: '',
    status: provider.status,
    remark: provider.remark ?? '',
    models: provider.models.map(toModelDraft),
  };
}

function toSaveRequest(draft: ProviderDraft): ProviderSaveRequest {
  return {
    name: draft.name.trim(),
    type: draft.type,
    baseUrl: draft.baseUrl.trim(),
    apiKey: draft.apiKey.trim() === '' ? undefined : draft.apiKey.trim(),
    status: draft.status,
    remark: draft.remark.trim() === '' ? undefined : draft.remark.trim(),
    models: draft.models.map(model => ({
      id: model.id,
      modelName: model.modelName.trim(),
      displayName: model.displayName.trim() === '' ? undefined : model.displayName.trim(),
      contextWindow: model.contextWindow.trim() === '' ? undefined : model.contextWindow.trim(),
      maxOutputTokens: model.maxOutputTokens.trim() === '' ? undefined : model.maxOutputTokens.trim(),
      inputTypes: model.inputTypes,
      reasoningLevels: model.reasoningLevels,
      status: model.status,
    })),
  };
}

/** 页面外壳：App 上下文（message / modal）必须挂在 ConfigProvider 内 */
export default function ModelConfigPage() {
  return (
    <ConfigProvider locale={zhCN} theme={MODEL_PAGE_THEME}>
      <App style={{ height: '100%' }}>
        <ModelConfigContent />
      </App>
    </ConfigProvider>
  );
}

function ModelConfigContent() {
  const { message, modal } = App.useApp();

  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState<string | null>(null);
  const [types, setTypes] = useState<ProviderTypeOption[]>([]);
  const [defaultLevels, setDefaultLevels] = useState<ReasoningLevel[]>([]);

  // 供应商编辑抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [draft, setDraft] = useState<ProviderDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [enrichingId, setEnrichingId] = useState<number | null>(null);

  // 模型编辑弹窗
  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [modelDraft, setModelDraft] = useState<ModelDraft | null>(null);
  const [modelIndex, setModelIndex] = useState<number | null>(null);

  // 远程模型选择弹窗
  const [remoteOpen, setRemoteOpen] = useState(false);
  const [remoteLoading, setRemoteLoading] = useState(false);
  const [remoteModels, setRemoteModels] = useState<RemoteModelInfo[]>([]);
  const [remoteSelected, setRemoteSelected] = useState<string[]>([]);
  const [remoteKeyword, setRemoteKeyword] = useState('');

  // 模型可用性测试弹窗（按供应商维度打开，列出其下已配置模型）
  const [modelTestProvider, setModelTestProvider] = useState<ProviderConfig | null>(null);
  const [testSelected, setTestSelected] = useState<number[]>([]);
  const [testResults, setTestResults] = useState<Record<number, ModelTestResult>>({});
  const [testingModelIds, setTestingModelIds] = useState<number[]>([]);
  const [batchTesting, setBatchTesting] = useState(false);

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [providerData, typeData, levelData] = await Promise.all([
        listProviders(),
        listProviderTypes(),
        listDefaultReasoningLevels(),
      ]);
      setProviders(providerData);
      setTypes(typeData);
      setDefaultLevels(levelData);
      setPageError(null);
    } catch (error) {
      setPageError(describeError(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // ------------------------------------------------------------
  // 供应商操作

  const openCreate = () => {
    const base = emptyDraft();
    if (types.length > 0) {
      base.type = types[0].value;
      base.baseUrl = types[0].defaultBaseUrl;
    }
    setDraft(base);
    setDrawerOpen(true);
  };

  const openEdit = (provider: ProviderConfig) => {
    setDraft(toProviderDraft(provider));
    setDrawerOpen(true);
  };

  /**
   * 关闭抽屉（遮罩点击 / ESC / 取消按钮共用）：清空草稿，未保存的改动直接丢弃。
   * 遮罩可关闭是有意为之——与 antd Drawer 默认行为一致，避免用户误以为界面卡住。
   */
  const closeDrawer = () => {
    setDrawerOpen(false);
    setDraft(null);
  };

  const handleTypeChange = (value: string) => {
    if (!draft) {
      return;
    }
    const option = types.find(type => type.value === value);
    // 仅在 Base URL 为空或仍是旧类型的默认值时自动覆盖，避免冲掉用户手填值
    const previous = types.find(type => type.value === draft.type);
    const shouldFill = draft.baseUrl.trim() === '' || draft.baseUrl === previous?.defaultBaseUrl;
    setDraft({
      ...draft,
      type: value,
      baseUrl: shouldFill ? option?.defaultBaseUrl ?? draft.baseUrl : draft.baseUrl,
    });
  };

  const saveDraft = async () => {
    if (!draft) {
      return;
    }
    if (draft.name.trim() === '') {
      message.warning('请填写供应商名称');
      return;
    }
    if (draft.baseUrl.trim() === '') {
      message.warning('请填写接口地址（Base URL）');
      return;
    }
    if (!draft.id && draft.apiKey.trim() === '') {
      message.warning('新增供应商需要填写 API Key（本地服务可填任意值）');
      return;
    }
    const duplicated = draft.models.filter(
      model => model.modelName.trim() !== '',
    ).map(model => model.modelName.trim());
    if (new Set(duplicated).size !== duplicated.length) {
      message.warning('同一供应商下模型名称不能重复');
      return;
    }
    if (duplicated.some(name => name === '')) {
      message.warning('模型名称不能为空');
      return;
    }

    setSaving(true);
    try {
      const request = toSaveRequest(draft);
      if (draft.id) {
        await updateProvider(draft.id, request);
        message.success('供应商已更新');
      } else {
        await createProvider(request);
        message.success('供应商已创建');
      }
      setDrawerOpen(false);
      setDraft(null);
      await loadAll();
    } catch (error) {
      message.error(`保存失败：${describeError(error)}`);
    } finally {
      setSaving(false);
    }
  };

  const runTest = async (provider: ProviderConfig) => {
    setTestingId(provider.id);
    try {
      const result = await testProvider(provider.id);
      if (result.success) {
        message.success(`${provider.name}：${result.message}（${result.elapsedMs}ms）`);
      } else {
        message.error(`${provider.name}：${result.message}（${result.elapsedMs}ms）`);
      }
    } catch (error) {
      message.error(`测试失败：${describeError(error)}`);
    } finally {
      setTestingId(null);
    }
  };

  // ------------------------------------------------------------
  // 模型可用性测试（弹窗内单条 / 批量）

  const openModelTester = (provider: ProviderConfig) => {
    setModelTestProvider(provider);
    // 默认全选：常见诉求是「一次把所有模型都验一遍」
    setTestSelected(provider.models.map(model => model.id));
    setTestResults({});
    setTestingModelIds([]);
    setBatchTesting(false);
  };

  const closeModelTester = () => {
    setModelTestProvider(null);
    setTestSelected([]);
    setTestResults({});
    setTestingModelIds([]);
    setBatchTesting(false);
  };

  /** 测试单个模型（弹窗内点某行的「测试」） */
  const runModelTest = async (modelId: number) => {
    setTestingModelIds(current => (current.includes(modelId) ? current : [...current, modelId]));
    try {
      const result = await testModel(modelId);
      setTestResults(current => ({ ...current, [modelId]: result }));
    } catch (error) {
      // 接口级失败（网络 / 401 等整体错误）也落成一条结果，避免弹窗里"点了没反应"
      setTestResults(current => ({
        ...current,
        [modelId]: {
          modelId,
          modelName: null,
          displayName: null,
          success: false,
          message: describeError(error),
          elapsedMs: 0,
          reply: null,
        },
      }));
    } finally {
      setTestingModelIds(current => current.filter(id => id !== modelId));
    }
  };

  /** 批量测试所选模型（后端逐条测试，单条失败不影响其余） */
  const runBatchModelTest = async () => {
    if (testSelected.length === 0) {
      return;
    }
    setBatchTesting(true);
    setTestingModelIds(testSelected);
    try {
      const results = await testModels(testSelected);
      setTestResults(current => {
        const next = { ...current };
        for (const result of results) {
          next[result.modelId] = result;
        }
        return next;
      });
      const failed = results.filter(result => !result.success).length;
      if (failed === 0) {
        message.success(`${results.length} 个模型全部可用`);
      } else {
        message.warning(`${results.length} 个模型中 ${failed} 个不可用`);
      }
    } catch (error) {
      message.error(`批量测试失败：${describeError(error)}`);
    } finally {
      setBatchTesting(false);
      setTestingModelIds([]);
    }
  };

  const runEnrich = async (provider: ProviderConfig) => {
    setEnrichingId(provider.id);
    try {
      await enrichModels(provider.id);
      await loadAll();
      message.success('已按目录补全模型窗口信息');
    } catch (error) {
      message.error(`补全失败：${describeError(error)}`);
    } finally {
      setEnrichingId(null);
    }
  };

  const confirmDelete = (provider: ProviderConfig) => {
    modal.confirm({
      title: '删除供应商',
      content: (
        <>
          确定删除「{provider.name}」吗？其下 {provider.modelCount} 个模型将被
          <Typography.Text strong> 一并删除 </Typography.Text>
          ，操作不可撤销。
        </>
      ),
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProvider(provider.id);
          message.success('供应商已删除');
          await loadAll();
        } catch (error) {
          message.error(`删除失败：${describeError(error)}`);
        }
      },
    });
  };

  // ------------------------------------------------------------
  // 模型操作（草稿态，随供应商一起保存）

  const addModelRow = () => {
    if (!draft) {
      return;
    }
    const model: ModelDraft = {
      key: nextDraftKey(),
      modelName: '',
      displayName: '',
      contextWindow: '',
      maxOutputTokens: '',
      inputTypes: ['text'],
      reasoningLevels: defaultLevels.length > 0 ? defaultLevels : [],
      status: 1,
    };
    setDraft({ ...draft, models: [...draft.models, model] });
    setModelDraft(model);
    setModelIndex(draft.models.length);
    setModelModalOpen(true);
  };

  const editModelRow = (index: number) => {
    if (!draft) {
      return;
    }
    setModelDraft({ ...draft.models[index] });
    setModelIndex(index);
    setModelModalOpen(true);
  };

  const removeModelRow = (index: number) => {
    if (!draft) {
      return;
    }
    const removed = draft.models[index];
    setDraft({ ...draft, models: draft.models.filter((_, i) => i !== index) });
    if (removed.id) {
      message.info('已移除该模型，保存供应商后生效');
    }
  };

  const commitModelDraft = () => {
    if (!draft || !modelDraft || modelIndex === null) {
      return;
    }
    if (modelDraft.modelName.trim() === '') {
      message.warning('请填写模型名称');
      return;
    }
    const duplicate = draft.models.some(
      (model, i) =>
        i !== modelIndex &&
        model.modelName.trim().toLowerCase() === modelDraft.modelName.trim().toLowerCase(),
    );
    if (duplicate) {
      message.warning('同一供应商下模型名称不能重复');
      return;
    }
    const nextModels = [...draft.models];
    nextModels[modelIndex] = modelDraft;
    // 编辑草稿里改名后同步刷新供应商名列表（用于唯一性提示）
    setDraft({ ...draft, models: nextModels });
    setModelModalOpen(false);
    setModelDraft(null);
    setModelIndex(null);
  };

  // ------------------------------------------------------------
  // 一键获取远程模型

  const openRemotePicker = async () => {
    if (!draft) {
      return;
    }
    // 新供应商尚未保存：直接用草稿里的 Base URL / API Key 拉取，免去「先存一遍才看得到模型」
    if (draft.baseUrl.trim() === '') {
      message.warning('请先填写接口地址（Base URL）');
      return;
    }
    setRemoteOpen(true);
    setRemoteLoading(true);
    setRemoteModels([]);
    setRemoteSelected([]);
    setRemoteKeyword('');
    try {
      const list = draft.id
        ? await listRemoteModels(draft.id)
        : await listRemoteModelsByDraft({
            type: draft.type,
            baseUrl: draft.baseUrl.trim(),
            apiKey: draft.apiKey.trim() === '' ? undefined : draft.apiKey.trim(),
          });
      setRemoteModels(list);
      if (list.length === 0) {
        message.info('该供应商未返回任何模型');
      }
    } catch (error) {
      message.error(`获取模型列表失败：${describeError(error)}`);
    } finally {
      setRemoteLoading(false);
    }
  };

  const applyRemoteSelection = () => {
    if (!draft) {
      return;
    }
    const existing = new Set(draft.models.map(model => model.modelName.toLowerCase()));
    const additions: ModelDraft[] = [];
    for (const model of remoteModels) {
      if (!remoteSelected.includes(model.id) || existing.has(model.id.toLowerCase())) {
        continue;
      }
      additions.push({
        key: nextDraftKey(),
        modelName: model.id,
        displayName: model.id,
        contextWindow: model.contextWindow ? String(model.contextWindow) : '',
        maxOutputTokens: model.maxOutputTokens ? String(model.maxOutputTokens) : '',
        inputTypes: model.inputTypes && model.inputTypes.length > 0 ? model.inputTypes : ['text'],
        reasoningLevels: defaultLevels.length > 0 ? defaultLevels : [],
        status: 1,
      });
    }
    setDraft({ ...draft, models: [...draft.models, ...additions] });
    setRemoteOpen(false);
    message.success(`已填入 ${additions.length} 个模型，保存供应商后生效`);
  };

  // ------------------------------------------------------------
  // 渲染

  const providerColumns: TableColumnsType<ProviderConfig> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 180,
      render: (name: string, record) => (
        <Space size={6}>
          <Typography.Text strong>{name}</Typography.Text>
          {record.status === 1 ? null : <Tag>已禁用</Tag>}
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 120,
      render: (type: string) => {
        const label = types.find(item => item.value === type)?.label ?? type;
        return <Tag color="blue">{label}</Tag>;
      },
    },
    {
      title: '接口地址',
      dataIndex: 'baseUrl',
      ellipsis: true,
    },
    {
      title: 'API Key',
      dataIndex: 'apiKeyHint',
      width: 150,
      render: (hint: string | null, record) =>
        record.hasApiKey ? <Typography.Text code>{hint}</Typography.Text> : <Tag>未配置</Tag>,
    },
    {
      title: '模型',
      dataIndex: 'modelCount',
      width: 80,
      render: (count: number) => <Tag color={count > 0 ? 'geekblue' : 'default'}>{count}</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 300,
      render: (_, record) => (
        <Space size={0} wrap>
          {/* 测试拆成两段：主按钮测供应商连通性；右侧下拉图标直接弹出「模型可用性测试」弹窗
              （点一下即出模型列表，不再多一层菜单） */}
          <Button
            type="link"
            size="small"
            icon={<ApiOutlined />}
            loading={testingId === record.id}
            onClick={() => runTest(record)}
          >
            测试
          </Button>
          <Tooltip
            title={
              record.modelCount === 0
                ? '该供应商下还没有模型'
                : '测试模型可用性（支持批量）'
            }
          >
            <Button
              type="link"
              size="small"
              aria-label="测试模型可用性"
              icon={<DownOutlined />}
              disabled={record.modelCount === 0}
              onClick={() => openModelTester(record)}
            />
          </Tooltip>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Tooltip title="按 models.dev 目录补全已保存模型缺失的窗口信息">
            <Button
              type="link"
              size="small"
              icon={<ReloadOutlined />}
              loading={enrichingId === record.id}
              disabled={record.modelCount === 0}
              onClick={() => runEnrich(record)}
            >
              补全
            </Button>
          </Tooltip>
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => confirmDelete(record)}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ];

  const draftModelColumns: TableColumnsType<ModelDraft> = [
    {
      title: '模型名称',
      dataIndex: 'modelName',
      ellipsis: true,
      render: (name: string) => name || <Typography.Text type="secondary">未命名</Typography.Text>,
    },
    {
      title: '显示名称',
      dataIndex: 'displayName',
      ellipsis: true,
      render: (name: string) => name || '-',
    },
    {
      title: '上下文窗口',
      dataIndex: 'contextWindow',
      width: 110,
      render: (value: string) => (value ? value : <Typography.Text type="secondary">自动</Typography.Text>),
    },
    {
      title: '最大输出',
      dataIndex: 'maxOutputTokens',
      width: 100,
      render: (value: string) => (value ? value : <Typography.Text type="secondary">自动</Typography.Text>),
    },
    {
      title: '输入类型',
      dataIndex: 'inputTypes',
      width: 130,
      render: (values: string[]) => (
        <Space size={4}>
          {values.includes('text') ? <Tag>文本</Tag> : null}
          {values.includes('image') ? <Tag color="purple">图片</Tag> : null}
        </Space>
      ),
    },
    {
      title: '推理档位',
      dataIndex: 'reasoningLevels',
      width: 200,
      render: (levels: ReasoningLevel[]) => (
        <Space size={4} wrap>
          {levels.length === 0 ? (
            <Typography.Text type="secondary">默认</Typography.Text>
          ) : (
            levels.map(level => (
              <Tooltip key={level.key} title={`API 参数值：${level.apiValue || '（不指定）'}`}>
                <Tag>{level.label}</Tag>
              </Tooltip>
            ))
          )}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_, __, index) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => editModelRow(index)}>
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => removeModelRow(index)}
          >
            移除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="model-config">
      <header className="page-header">
        <div>
          <h1>模型配置</h1>
          <p className="chat-sub">
            维护模型供应商与其下模型；流式对话页可切换模型（默认选项走后台 .env 配置）
          </p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => loadAll()} disabled={loading}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增供应商
          </Button>
        </Space>
      </header>

      {pageError ? (
        <Alert
          className="model-config-alert"
          type="error"
          showIcon
          closable
          message="加载失败"
          description={pageError}
          onClose={() => setPageError(null)}
        />
      ) : null}

      <div className="model-config-body">
        <Table
          columns={providerColumns}
          dataSource={providers}
          rowKey="id"
          loading={loading}
          pagination={false}
          locale={{
            emptyText: (
              <Empty
                description="还没有供应商，先新增一个（如 OpenAI / DeepSeek / 本地 Ollama）"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ),
          }}
        />
      </div>

      {/* 供应商编辑抽屉：基础信息 + 模型列表 */}
      <Drawer
        title={draft?.id ? '编辑供应商' : '新增供应商'}
        open={drawerOpen}
        width={960}
        onClose={closeDrawer}
        // 操作按钮固定在右下角（保存靠左、取消靠右），长列表滚动时始终可见
        footer={
          <div className="drawer-footer">
            <Space>
              <Button type="primary" loading={saving} onClick={saveDraft}>
                保存
              </Button>
              <Button onClick={closeDrawer}>取消</Button>
            </Space>
          </div>
        }
      >
        {draft ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Form layout="vertical" className="model-config-form">
              <Form.Item label="供应商名称" required>
                <Input
                  value={draft.name}
                  placeholder="如：我的 OpenAI"
                  maxLength={128}
                  onChange={event => setDraft({ ...draft, name: event.target.value })}
                />
              </Form.Item>
              <Form.Item label="供应商类型" extra="选择类型会自动填入默认 Base URL（可自行修改）">
                <Select
                  value={draft.type}
                  options={types.map(type => ({ label: type.label, value: type.value }))}
                  onChange={handleTypeChange}
                />
              </Form.Item>
              <Form.Item label="接口地址（Base URL）" required extra="不带 /chat/completions；缺 /v1 时后端会自动补全">
                <Input
                  value={draft.baseUrl}
                  placeholder="https://api.openai.com/v1"
                  onChange={event => setDraft({ ...draft, baseUrl: event.target.value })}
                />
              </Form.Item>
              <Form.Item
                label="API Key"
                extra={draft.id ? '留空表示不修改（当前已配置则不覆盖）' : '本地 Ollama 等无需鉴权的服务可填任意值'}
              >
                <SecretInput
                  value={draft.apiKey}
                  placeholder={draft.id ? '留空不修改' : 'sk-...'}
                  onChange={value => setDraft({ ...draft, apiKey: value })}
                />
              </Form.Item>
              <Form.Item label="启用">
                <Switch
                  checked={draft.status === 1}
                  onChange={checked => setDraft({ ...draft, status: checked ? 1 : 0 })}
                />
              </Form.Item>
              <Form.Item label="备注">
                <Input
                  value={draft.remark}
                  maxLength={512}
                  onChange={event => setDraft({ ...draft, remark: event.target.value })}
                />
              </Form.Item>
            </Form>

            <Card
              size="small"
              title={`模型（${draft.models.length}）`}
              extra={
                <Space>
                  <Tooltip title="拉取供应商 /models 并按需填入（无需先保存供应商）">
                    <Button
                      size="small"
                      icon={<CloudDownloadOutlined />}
                      disabled={draft.baseUrl.trim() === ''}
                      onClick={openRemotePicker}
                    >
                      一键获取模型
                    </Button>
                  </Tooltip>
                  <Button size="small" type="primary" icon={<PlusOutlined />} onClick={addModelRow}>
                    手动添加
                  </Button>
                </Space>
              }
            >
              <Table
                columns={draftModelColumns}
                dataSource={draft.models}
                rowKey="key"
                size="small"
                pagination={false}
                locale={{ emptyText: '还没有模型：可「一键获取模型」或「手动添加」' }}
              />
            </Card>
          </Space>
        ) : null}
      </Drawer>

      {/* 模型编辑弹窗 */}
      <Modal
        title={modelDraft?.id ? '编辑模型' : '新增模型'}
        open={modelModalOpen}
        width={720}
        onCancel={() => {
          setModelModalOpen(false);
          setModelDraft(null);
          setModelIndex(null);
        }}
        onOk={commitModelDraft}
        okText="确定"
        cancelText="取消"
        maskClosable={false}
      >
        {modelDraft ? (
          <ModelDraftEditor
            draft={modelDraft}
            onChange={setModelDraft}
            onFetchMetadata={async () => {
              try {
                const info = await fetchMetadataFor(draft?.type, modelDraft.modelName);
                if (!info) {
                  message.info('未在目录中命中该模型，请手工填写');
                  return;
                }
                setModelDraft({
                  ...modelDraft,
                  contextWindow: info.contextWindow ? String(info.contextWindow) : modelDraft.contextWindow,
                  maxOutputTokens: info.maxOutputTokens
                    ? String(info.maxOutputTokens)
                    : modelDraft.maxOutputTokens,
                  inputTypes:
                    info.inputTypes && info.inputTypes.length > 0 ? info.inputTypes : modelDraft.inputTypes,
                });
                message.success('已补全元信息');
              } catch (error) {
                message.error(`获取失败：${describeError(error)}`);
              }
            }}
          />
        ) : null}
      </Modal>

      {/* 远程模型选择弹窗 */}
      <Modal
        title="供应商官方模型列表"
        open={remoteOpen}
        width={640}
        onCancel={() => setRemoteOpen(false)}
        onOk={applyRemoteSelection}
        okText={`填入所选（${remoteSelected.length}）`}
        okButtonProps={{ disabled: remoteSelected.length === 0 }}
        cancelText="取消"
      >
        <Spin spinning={remoteLoading}>
          <Input.Search
            placeholder="搜索模型名称"
            allowClear
            value={remoteKeyword}
            onChange={event => setRemoteKeyword(event.target.value)}
            style={{ marginBottom: 12 }}
          />
          <RemoteModelList
            models={remoteModels}
            keyword={remoteKeyword}
            existing={draft?.models.map(model => model.modelName) ?? []}
            selected={remoteSelected}
            onSelectedChange={setRemoteSelected}
          />
        </Spin>
      </Modal>

      {/* 模型可用性测试弹窗：列出该供应商已配置模型，支持单测与多选批量测试 */}
      <Modal
        title={modelTestProvider ? `测试模型可用性 · ${modelTestProvider.name}` : '测试模型可用性'}
        open={modelTestProvider !== null}
        width={920}
        onCancel={closeModelTester}
        maskClosable={false}
        styles={{ body: { paddingTop: 4 } }}
        footer={
          <div className="model-test-footer">
            {/* 左下角说明收成「标题 + ? 气泡」，正文移入气泡，避免占满底部一行 */}
            <div className="model-test-hint">
              <Typography.Text type="secondary" className="model-test-hint-label">
                模型测试说明
              </Typography.Text>
              <Popover
                placement="topLeft"
                title="模型测试说明"
                content={
                  <div className="model-test-hint-popover">
                    <p>
                      <strong>测试方式</strong>：向模型发送固定提示词「请回复pong」，能拿到回复即视为可用。
                    </p>
                    <p>
                      <strong>请求参数</strong>：max_tokens 512、temperature 0，并下发{' '}
                      <code>enable_thinking=false</code> / <code>thinking=disabled</code>{' '}
                      关闭思考（部分模型会忽略该字段，仍可能把额度花在思维链上）。
                    </p>
                    <p>
                      <strong>判定口径</strong>：有正文 → 可用；正文为空但有思考内容 → 仍算可用（会在结果里注明）；
                      两者都没有 → 不可用，并附 finish_reason 便于排查（length 表示额度被思考占满）。
                    </p>
                    <p className="model-test-hint-warn">
                      仅验证「模型能被调用」，不等同于能力评测。
                    </p>
                  </div>
                }
              >
                <QuestionCircleOutlined className="model-test-hint-icon" aria-label="模型测试说明" />
              </Popover>
            </div>
            <div className="model-test-actions">
              <Button onClick={closeModelTester}>关闭</Button>
              <Button
                type="primary"
                loading={batchTesting}
                disabled={testSelected.length === 0}
                onClick={runBatchModelTest}
              >
                批量测试 {testSelected.length} 个模型
              </Button>
            </div>
          </div>
        }
      >
        {modelTestProvider ? (
          <ModelTesterTable
            provider={modelTestProvider}
            selected={testSelected}
            onSelectedChange={setTestSelected}
            results={testResults}
            testingModelIds={testingModelIds}
            batchTesting={batchTesting}
            onTestOne={runModelTest}
          />
        ) : null}
      </Modal>
    </div>
  );
}

/** 模型测试列表（多选 + 单条测试 + 结果展示） */
function ModelTesterTable({
  provider,
  selected,
  onSelectedChange,
  results,
  testingModelIds,
  batchTesting,
  onTestOne,
}: {
  provider: ProviderConfig;
  selected: number[];
  onSelectedChange: (ids: number[]) => void;
  results: Record<number, ModelTestResult>;
  testingModelIds: number[];
  batchTesting: boolean;
  onTestOne: (modelId: number) => void;
}) {
  const allIds = provider.models.map(model => model.id);
  const allSelected = allIds.length > 0 && allIds.every(id => selected.includes(id));

  const columns: TableColumnsType<ModelConfig> = [
    {
      title: '模型',
      dataIndex: 'displayName',
      ellipsis: true,
      render: (displayName: string | null, record) => (
        <Space direction="vertical" size={2}>
          <Typography.Text strong>{displayName || record.modelName}</Typography.Text>
          {displayName && displayName !== record.modelName ? (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {record.modelName}
            </Typography.Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (status: number) =>
        status === 1 ? <Tag color="green">启用</Tag> : <Tag>已禁用</Tag>,
    },
    {
      title: '测试结果',
      key: 'result',
      // 结果列吃掉剩余宽度（模型列不设宽，跟随内容伸展）
      render: (_, record) => {
        const testing = testingModelIds.includes(record.id);
        const result = results[record.id];
        if (testing) {
          return (
            <Space size={8}>
              <Spin size="small" />
              <Typography.Text type="secondary">测试中…</Typography.Text>
            </Space>
          );
        }
        if (!result) {
          return <Typography.Text type="secondary">未测试</Typography.Text>;
        }
        return (
          <div className="model-test-result">
            <Space size={8}>
              {result.success ? <Tag color="green">可用</Tag> : <Tag color="red">不可用</Tag>}
              {result.elapsedMs > 0 ? (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {result.elapsedMs}ms
                </Typography.Text>
              ) : null}
            </Space>
            <Tooltip title={result.message}>
              <div className={`model-test-message${result.success ? '' : ' is-error'}`}>
                {result.message}
              </div>
            </Tooltip>
          </div>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Button
          type="link"
          size="small"
          icon={<ApiOutlined />}
          loading={testingModelIds.includes(record.id)}
          disabled={batchTesting}
          onClick={() => onTestOne(record.id)}
        >
          测试
        </Button>
      ),
    },
  ];

  if (provider.models.length === 0) {
    return (
      <Empty
        description="该供应商下还没有模型，请先在「编辑」里添加"
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <Table
      columns={columns}
      dataSource={provider.models}
      rowKey="id"
      // middle：行高比 small 宽松（弹窗里 small 过于拥挤）
      size="middle"
      pagination={false}
      scroll={{ y: 400 }}
      rowSelection={{
        selectedRowKeys: selected,
        onChange: keys => onSelectedChange(keys.map(Number)),
        columnTitle: (
          <Checkbox
            checked={allSelected}
            indeterminate={!allSelected && selected.length > 0}
            onChange={event => onSelectedChange(event.target.checked ? allIds : [])}
          />
        ),
      }}
    />
  );
}

/** 单模型元信息查询（命令式，用于「按目录补全」按钮） */
async function fetchMetadataFor(
  providerType: string | undefined,
  modelName: string,
): Promise<RemoteModelInfo | null> {
  if (modelName.trim() === '') {
    return null;
  }
  const info = await getModelMetadata(modelName.trim(), providerType);
  if (!info || (!info.contextWindow && !info.maxOutputTokens)) {
    return null;
  }
  return info;
}

/** 远程模型多选列表（按关键字过滤 + 已配置标记） */
function RemoteModelList({
  models,
  keyword,
  existing,
  selected,
  onSelectedChange,
}: {
  models: RemoteModelInfo[];
  keyword: string;
  existing: string[];
  selected: string[];
  onSelectedChange: (values: string[]) => void;
}) {
  const existingSet = new Set(existing.map(name => name.toLowerCase()));
  const filtered = keyword.trim() === ''
    ? models
    : models.filter(model => model.id.toLowerCase().includes(keyword.trim().toLowerCase()));

  if (models.length === 0) {
    return <Empty description="没有可填入的模型" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  if (filtered.length === 0) {
    return <Empty description="没有匹配的模型" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const toggle = (id: string, checked: boolean) => {
    if (checked) {
      onSelectedChange([...selected, id]);
    } else {
      onSelectedChange(selected.filter(item => item !== id));
    }
  };

  const selectableIds = filtered.filter(model => !existingSet.has(model.id.toLowerCase())).map(m => m.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every(id => selected.includes(id));

  return (
    <div className="remote-model-list">
      <div className="remote-model-toolbar">
        <Checkbox
          checked={allSelected}
          indeterminate={!allSelected && selectableIds.some(id => selected.includes(id))}
          disabled={selectableIds.length === 0}
          onChange={event =>
            onSelectedChange(event.target.checked ? Array.from(new Set([...selected, ...selectableIds])) : [])
          }
        >
          全选当前列表
        </Checkbox>
        <Typography.Text type="secondary">
          共 {filtered.length} 个，已配置 {filtered.filter(m => existingSet.has(m.id.toLowerCase())).length} 个
        </Typography.Text>
      </div>
      {filtered.map(model => {
        const added = existingSet.has(model.id.toLowerCase());
        return (
          <label key={model.id} className={`remote-model-row${added ? ' is-added' : ''}`}>
            <Checkbox
              checked={added || selected.includes(model.id)}
              disabled={added}
              onChange={event => toggle(model.id, event.target.checked)}
            />
            <span className="remote-model-name">{model.id}</span>
            <span className="remote-model-meta">
              {model.contextWindow ? `上下文 ${formatTokens(model.contextWindow)}` : '窗口未知'}
              {model.maxOutputTokens ? ` · 输出 ${formatTokens(model.maxOutputTokens)}` : ''}
              {model.inputTypes?.includes('image') ? ' · 支持图片' : ''}
              {added ? ' · 已配置' : ''}
            </span>
          </label>
        );
      })}
    </div>
  );
}

/** 模型编辑表单（含推理档位增删与 API 参数值覆盖） */
function ModelDraftEditor({
  draft,
  onChange,
  onFetchMetadata,
}: {
  draft: ModelDraft;
  onChange: (draft: ModelDraft) => void;
  onFetchMetadata: () => Promise<void>;
}) {
  const [fetching, setFetching] = useState(false);

  const updateLevel = (index: number, patch: Partial<ReasoningLevel>) => {
    const levels = draft.reasoningLevels.map((level, i) => (i === index ? { ...level, ...patch } : level));
    onChange({ ...draft, reasoningLevels: levels });
  };

  const addLevel = () => {
    onChange({
      ...draft,
      reasoningLevels: [
        ...draft.reasoningLevels,
        { key: `level${draft.reasoningLevels.length + 1}`, label: '', apiValue: '' },
      ],
    });
  };

  const removeLevel = (index: number) => {
    onChange({
      ...draft,
      reasoningLevels: draft.reasoningLevels.filter((_, i) => i !== index),
    });
  };

  const resetLevels = () => {
    onChange({
      ...draft,
      reasoningLevels: [
        { key: 'off', label: '关闭', apiValue: 'off' },
        { key: 'low', label: '低', apiValue: 'low' },
        { key: 'medium', label: '中', apiValue: 'medium' },
        { key: 'high', label: '高', apiValue: 'high' },
      ],
    });
  };

  return (
    <Form layout="vertical" className="model-config-form">
      <Form.Item label="模型名称" required extra="调用时下发给供应商的 model 字段，如 gpt-4o / deepseek-chat">
        <Input
          value={draft.modelName}
          placeholder="gpt-4o"
          onChange={event => onChange({ ...draft, modelName: event.target.value })}
        />
      </Form.Item>
      <Form.Item label="显示名称" extra="留空则与模型名称一致">
        <Input
          value={draft.displayName}
          placeholder="GPT-4o"
          onChange={event => onChange({ ...draft, displayName: event.target.value })}
        />
      </Form.Item>
      <Space size={16} style={{ width: '100%' }} align="start">
        <Form.Item
          label="上下文窗口"
          extra="支持 1M / 200K 简写；留空自动获取"
          style={{ width: 260 }}
        >
          <Input
            value={draft.contextWindow}
            placeholder="如 128K 或 1M"
            onChange={event => onChange({ ...draft, contextWindow: event.target.value })}
          />
        </Form.Item>
        <Form.Item
          label="最大输出 tokens"
          extra="支持 256K 简写；留空自动获取"
          style={{ width: 260 }}
        >
          <Input
            value={draft.maxOutputTokens}
            placeholder="如 8K 或 256K"
            onChange={event => onChange({ ...draft, maxOutputTokens: event.target.value })}
          />
        </Form.Item>
        <Form.Item label=" " style={{ marginTop: 26 }}>
          <Button
            icon={<CloudDownloadOutlined />}
            loading={fetching}
            onClick={async () => {
              setFetching(true);
              try {
                await onFetchMetadata();
              } finally {
                setFetching(false);
              }
            }}
          >
            按目录补全
          </Button>
        </Form.Item>
      </Space>
      <Form.Item label="输入类型">
        <Checkbox.Group
          options={INPUT_TYPE_OPTIONS}
          value={draft.inputTypes}
          onChange={values => onChange({ ...draft, inputTypes: values as string[] })}
        />
      </Form.Item>

      <Form.Item
        label="预设推理档位"
        extra="off/low/medium/high 为默认档位，可增删；API 参数值为实际下发值（off = 关闭思考，留空 = 不指定）"
      >
        <div className="reasoning-levels">
          {draft.reasoningLevels.length === 0 ? (
            <Typography.Text type="secondary">未配置档位，聊天页只显示「默认」</Typography.Text>
          ) : (
            draft.reasoningLevels.map((level, index) => (
              <div className="reasoning-level-row" key={`${level.key}-${index}`}>
                <Input
                  value={level.key}
                  placeholder="key（如 low）"
                  onChange={event => updateLevel(index, { key: event.target.value })}
                  style={{ width: 140 }}
                />
                <Input
                  value={level.label}
                  placeholder="显示名（如 低）"
                  onChange={event => updateLevel(index, { label: event.target.value })}
                  style={{ width: 160 }}
                />
                <Input
                  value={level.apiValue}
                  placeholder="API 参数值（如 low / off）"
                  onChange={event => updateLevel(index, { apiValue: event.target.value })}
                  style={{ width: 200 }}
                />
                <Button
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => removeLevel(index)}
                />
              </div>
            ))
          )}
          <Space>
            <Button size="small" icon={<PlusOutlined />} onClick={addLevel}>
              新增档位
            </Button>
            <Button size="small" onClick={resetLevels}>
              恢复默认档位
            </Button>
          </Space>
        </div>
      </Form.Item>

      <Form.Item label="启用">
        <Switch
          checked={draft.status === 1}
          onChange={checked => onChange({ ...draft, status: checked ? 1 : 0 })}
        />
      </Form.Item>
    </Form>
  );
}
