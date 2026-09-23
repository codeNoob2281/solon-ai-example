import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  ConfigProvider,
  Input,
  Modal,
  Pagination,
  Radio,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { InboxOutlined, SyncOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import { ApiError, del, getJSON, postForm, postJSON } from '@/lib/api';

/**
 * 知识库管理页（rag-example 知识库管理接口的 Web 界面，UI 基于 antd v6）
 *
 * 功能：汇总统计条（文档数/块数/总字符/向量维度）+ 文档列表（标题/来源模糊搜索、
 * 状态多选、分页 10/20/50/100）+ 文档块预览 / 重建索引 / 删除（二次确认）/
 * 新增文档（粘贴内容 / 上传文件）。
 *
 * 数据获取：lib/api.ts fetch 薄封装 + 页面内 useState/useEffect（不引入 react-query）；
 * 查询参数状态保存在组件 state（hash 不承载查询参数）；所有变更操作成功后
 * 同时刷新「汇总 + 当前列表」，删除发生在最后一页仅一条时回退一页。
 * UI：antd ConfigProvider 主题对齐站点主色（#4f6ef7），locale 用 zhCN；
 * 确认弹窗走 App.useApp().modal（带上下文的静态方法），轻提示走 message。
 */

// ------------------------------------------------------------
// 类型（对应后端 DTO）

interface KbSummary {
  documentCount: number;
  chunkCount: number;
  totalChars: number;
  vectorDimension: number | null;
}

interface KbDocumentItem {
  id: string;
  title: string | null;
  source: string | null;
  contentType: string;
  docType: string | null;
  contentChars: number;
  chunkCount: number;
  status: 'INGESTED' | 'INGESTING' | 'FAILED';
  createdAt: string;
  updatedAt: string;
}

interface KbDocumentPage {
  total: number;
  pageNum: number;
  pageSize: number;
  pages: number;
  records: KbDocumentItem[];
}

interface KbChunkItem {
  id: string;
  chunkIndex: number;
  title: string | null;
  charCount: number;
  childCount: number;
  content: string;
}

interface DocumentMutationResult {
  documentId: string;
  chunkCount: number;
  childChunkCount: number;
  elapsedMs: number;
}

// ------------------------------------------------------------
// 常量

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

const STATUS_OPTIONS = [
  { value: 'INGESTED', label: '已入库' },
  { value: 'INGESTING', label: '处理中' },
  { value: 'FAILED', label: '失败' },
];

const STATUS_META: Record<KbDocumentItem['status'], { label: string; color: string }> = {
  INGESTED: { label: '已入库', color: 'green' },
  INGESTING: { label: '处理中', color: 'processing' },
  FAILED: { label: '失败', color: 'red' },
};

const ACCEPT_EXTENSIONS = '.md,.markdown,.txt,.html,.htm';

const UPLOAD_MAX_BYTES = 5 * 1024 * 1024;

/** antd 主题：主色 / 圆角对齐站点既有风格 */
const KB_THEME = {
  token: {
    colorPrimary: '#4f6ef7',
    borderRadius: 10,
    fontFamily: 'inherit',
  },
};

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function formatNumber(value: number): string {
  return value.toLocaleString('zh-CN');
}

/** 后端时间格式 yyyy-MM-dd HH:mm:ss → 展示 yyyy-MM-dd HH:mm */
function formatTime(value: string | null | undefined): string {
  return value ? value.slice(0, 16) : '-';
}

// ------------------------------------------------------------
// 页面

export default function KnowledgeBasePage() {
  return (
    <ConfigProvider locale={zhCN} theme={KB_THEME}>
      {/* App 默认 div：v6 的 CSS 变量作用域挂在 .ant-app 根节点上，component={false} 会导致主题失效；
          height:100% 保持高度链（否则 .kb 的 height:100% 解析失败，表格区 flex:1 失效、分页被顶出视口） */}
      <App style={{ height: '100%' }}>
        <KnowledgeBaseContent />
      </App>
    </ConfigProvider>
  );
}

/** 页面内容：App.useApp 必须在 <App> 之下的子组件里调用，不能放在渲染 <App> 的组件自身 */
function KnowledgeBaseContent() {
  const { message, modal } = App.useApp();

  // 汇总 + 列表数据
  const [summary, setSummary] = useState<KbSummary | null>(null);
  const [list, setList] = useState<KbDocumentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState<string | null>(null);

  // 查询参数（draft = 输入框草稿，applied = 已应用的查询条件；点击「查询」才生效）
  const [draftTitle, setDraftTitle] = useState('');
  const [draftSource, setDraftSource] = useState('');
  const [draftStatuses, setDraftStatuses] = useState<string[]>([]);
  const [appliedTitle, setAppliedTitle] = useState('');
  const [appliedSource, setAppliedSource] = useState('');
  const [appliedStatuses, setAppliedStatuses] = useState<string[]>([]);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 文档块预览弹窗
  const [previewDoc, setPreviewDoc] = useState<KbDocumentItem | null>(null);
  const [previewChunks, setPreviewChunks] = useState<KbChunkItem[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);

  // 新增文档弹窗
  const [addOpen, setAddOpen] = useState(false);
  const [addMode, setAddMode] = useState<'paste' | 'upload'>('paste');
  const [pasteTitle, setPasteTitle] = useState('');
  const [pasteFormat, setPasteFormat] = useState<'markdown' | 'text'>('markdown');
  const [pasteContent, setPasteContent] = useState('');
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadTitle, setUploadTitle] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // 行级操作 loading（重建索引）
  const [reindexingId, setReindexingId] = useState<string | null>(null);

  /** 拉取「汇总 + 列表」（显式传参，避免闭包旧值） */
  const loadAll = useCallback(
    async (page: number, size: number, title: string, source: string, statuses: string[]) => {
      setLoading(true);
      try {
        const params = new URLSearchParams();
        params.set('pageNum', String(page));
        params.set('pageSize', String(size));
        if (title) {
          params.set('title', title);
        }
        if (source) {
          params.set('source', source);
        }
        for (const status of statuses) {
          params.append('status', status);
        }
        const [summaryData, listData] = await Promise.all([
          getJSON<KbSummary>('/api/rag/kb/summary'),
          getJSON<KbDocumentPage>(`/api/rag/kb/documents?${params.toString()}`),
        ]);
        setSummary(summaryData);
        setList(listData);
        setPageError(null);
      } catch (error) {
        setPageError(errorMessage(error));
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  // 查询条件 / 分页变化即重查
  useEffect(() => {
    loadAll(pageNum, pageSize, appliedTitle, appliedSource, appliedStatuses);
  }, [loadAll, pageNum, pageSize, appliedTitle, appliedSource, appliedStatuses]);

  const applyFilters = () => {
    setPageNum(1);
    setAppliedTitle(draftTitle.trim());
    setAppliedSource(draftSource.trim());
    setAppliedStatuses(draftStatuses);
  };

  const resetFilters = () => {
    setDraftTitle('');
    setDraftSource('');
    setDraftStatuses([]);
    setPageNum(1);
    setAppliedTitle('');
    setAppliedSource('');
    setAppliedStatuses([]);
  };

  /** 变更操作成功后刷新「汇总 + 当前列表」 */
  const refresh = useCallback(
    (page = pageNum) => loadAll(page, pageSize, appliedTitle, appliedSource, appliedStatuses),
    [loadAll, pageNum, pageSize, appliedTitle, appliedSource, appliedStatuses],
  );

  // ------------------------------------------------------------
  // 操作

  const openPreview = async (doc: KbDocumentItem) => {
    setPreviewDoc(doc);
    setPreviewChunks([]);
    setPreviewLoading(true);
    try {
      const data = await getJSON<{ documentId: string; chunks: KbChunkItem[] }>(
        `/api/rag/kb/documents/${doc.id}/chunks`,
      );
      setPreviewChunks(data.chunks);
    } catch (error) {
      setPageError(errorMessage(error));
      setPreviewDoc(null);
    } finally {
      setPreviewLoading(false);
    }
  };

  const runReindex = async (doc: KbDocumentItem) => {
    setReindexingId(doc.id);
    setPageError(null);
    try {
      await postJSON<DocumentMutationResult>(`/api/rag/kb/documents/${doc.id}/reindex`);
      await refresh();
      message.success('重建索引完成');
    } catch (error) {
      message.error(`重建索引失败：${errorMessage(error)}`);
    } finally {
      setReindexingId(null);
    }
  };

  const confirmReindex = (doc: KbDocumentItem) => {
    modal.confirm({
      title: '重建索引',
      content: (
        <>
          确定对「{doc.title ?? '未命名文档'}」重建索引吗？将基于原始全文重新切分父块并重建向量子块，
          重建期间该文档的检索结果可能不完整。
        </>
      ),
      okText: '确认重建',
      cancelText: '取消',
      onOk: () => runReindex(doc),
    });
  };

  const runDelete = async (doc: KbDocumentItem) => {
    try {
      await del(`/api/rag/kb/documents/${doc.id}`);
      // 删除发生在最后一页仅一条时回退一页
      let targetPage = pageNum;
      if (list && list.records.length === 1 && pageNum > 1 && pageNum >= list.pages) {
        targetPage = pageNum - 1;
        setPageNum(targetPage);
      }
      await refresh(targetPage);
      message.success('删除成功');
    } catch (error) {
      message.error(`删除失败：${errorMessage(error)}`);
    }
  };

  const confirmDelete = (doc: KbDocumentItem) => {
    modal.confirm({
      title: '删除文档',
      content: (
        <>
          确定删除「{doc.title ?? '未命名文档'}」吗？将同时删除其 {formatNumber(doc.chunkCount ?? 0)}{' '}
          个父块与对应向量子块，操作不可恢复。
        </>
      ),
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => runDelete(doc),
    });
  };

  const submitPaste = async () => {
    if (!pasteContent.trim()) {
      setSubmitError('内容不能为空');
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const result = await postJSON<DocumentMutationResult>('/api/rag/kb/documents/paste', {
        title: pasteTitle.trim() || undefined,
        format: pasteFormat,
        content: pasteContent,
      });
      closeAddDialog();
      await refresh();
      message.success(
        `入库完成：父块 ${formatNumber(result.chunkCount)} · 子块 ${formatNumber(result.childChunkCount)}`,
      );
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  const submitUpload = async () => {
    if (!uploadFile) {
      setSubmitError('请选择文件');
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const form = new FormData();
      form.append('file', uploadFile);
      if (uploadTitle.trim()) {
        form.append('title', uploadTitle.trim());
      }
      const result = await postForm<DocumentMutationResult>('/api/rag/kb/documents/upload', form);
      closeAddDialog();
      await refresh();
      message.success(
        `入库完成：父块 ${formatNumber(result.chunkCount)} · 子块 ${formatNumber(result.childChunkCount)}`,
      );
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  const closeAddDialog = () => {
    setAddOpen(false);
    setPasteTitle('');
    setPasteFormat('markdown');
    setPasteContent('');
    setUploadFile(null);
    setUploadTitle('');
    setSubmitError(null);
  };

  // ------------------------------------------------------------
  // 渲染

  const columns: TableColumnsType<KbDocumentItem> = [
    {
      title: '标题',
      dataIndex: 'title',
      ellipsis: true,
      render: value => value ?? '未命名文档',
    },
    {
      title: '来源',
      dataIndex: 'source',
      ellipsis: true,
      render: value => value ?? <Typography.Text type="secondary">手动粘贴</Typography.Text>,
    },
    {
      title: '文档类型',
      dataIndex: 'docType',
      width: 100,
      render: value => <Tag>{value ?? '-'}</Tag>,
    },
    {
      title: '字符数',
      dataIndex: 'contentChars',
      align: 'right',
      width: 100,
      render: value => formatNumber(value ?? 0),
    },
    {
      title: '文档块数量',
      dataIndex: 'chunkCount',
      align: 'right',
      width: 110,
      render: value => formatNumber(value ?? 0),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (_, doc) => (
        <Tag
          color={STATUS_META[doc.status]?.color}
          icon={doc.status === 'INGESTING' ? <SyncOutlined spin /> : undefined}
        >
          {STATUS_META[doc.status]?.label ?? doc.status}
        </Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 150,
      render: value => formatTime(value),
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      fixed: 'right',
      render: (_, doc) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => openPreview(doc)} disabled={reindexingId === doc.id}>
            文档块预览
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => confirmReindex(doc)}
            disabled={reindexingId === doc.id || doc.status === 'INGESTING'}
          >
            {reindexingId === doc.id ? '重建中…' : '重建索引'}
          </Button>
          <Button
            type="link"
            size="small"
            danger
            onClick={() => confirmDelete(doc)}
            disabled={reindexingId === doc.id || doc.status === 'INGESTING'}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="kb">
          <header className="page-header">
            <div>
              <h1>知识库管理</h1>
              <p className="chat-sub">一个文件 / 一次粘贴 = 一条文档记录 · 父块存 MySQL · 子块存 Milvus</p>
            </div>
            <Button type="primary" onClick={() => setAddOpen(true)}>
              ＋ 新增文档
            </Button>
          </header>

          {/* 汇总统计条 */}
          <div className="kb-summary">
            <Card size="small">
              <Statistic title="文档数" value={summary ? formatNumber(summary.documentCount) : '-'} />
            </Card>
            <Card size="small">
              <Statistic title="文档块数" value={summary ? formatNumber(summary.chunkCount) : '-'} />
            </Card>
            <Card size="small">
              <Statistic title="文档总字符" value={summary ? formatNumber(summary.totalChars) : '-'} />
            </Card>
            <Card size="small">
              <Statistic
                title="向量维度"
                value={summary?.vectorDimension != null ? String(summary.vectorDimension) : '-'}
              />
            </Card>
          </div>

          {/* 搜索工具栏 */}
          <Space wrap size={8} style={{ marginTop: 12 }}>
            <Input
              allowClear
              placeholder="标题（模糊）"
              style={{ width: 180 }}
              value={draftTitle}
              onChange={event => setDraftTitle(event.target.value)}
              onPressEnter={applyFilters}
            />
            <Input
              allowClear
              placeholder="来源（模糊）"
              style={{ width: 220 }}
              value={draftSource}
              onChange={event => setDraftSource(event.target.value)}
              onPressEnter={applyFilters}
            />
            <Select
              mode="multiple"
              allowClear
              placeholder="状态（全部）"
              options={STATUS_OPTIONS}
              value={draftStatuses}
              onChange={values => setDraftStatuses(values)}
              style={{ minWidth: 200 }}
              maxTagCount="responsive"
            />
            <Button type="primary" onClick={applyFilters}>
              查询
            </Button>
            <Button onClick={resetFilters}>重置</Button>
          </Space>

          {pageError ? (
            <Alert
              type="error"
              showIcon
              closable={{ onClose: () => setPageError(null) }}
              title={pageError}
              style={{ marginTop: 12 }}
            />
          ) : null}

          {/* 文档列表：不抢占剩余空间（0 1 auto）——行数少时高度即内容高度，分页紧跟列表；
              行数多时被压缩（min-height:0）转为内部滚动，分页钉在底部 */}
          <div style={{ flex: '0 1 auto', minHeight: 0, marginTop: 12, overflow: 'auto' }}>
            <Table<KbDocumentItem>
              rowKey="id"
              size="medium"
              columns={columns}
              dataSource={list?.records ?? []}
              loading={loading}
              pagination={false}
              scroll={{ x: 1080 }}
            />
          </div>

          {/* 分页条：右对齐；距页面底部 40px（.kb padding-bottom） */}
          <Pagination
            style={{ marginTop: 12, alignSelf: 'flex-end' }}
            current={pageNum}
            pageSize={pageSize}
            total={list?.total ?? 0}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
            showSizeChanger
            showTotal={total => `共 ${formatNumber(total)} 条`}
            onChange={(page, size) => {
              if (size !== pageSize) {
                setPageNum(1);
                setPageSize(size);
              } else {
                setPageNum(page);
              }
            }}
          />

          {/* 文档块预览弹窗 */}
          <Modal
            open={previewDoc !== null}
            onCancel={() => setPreviewDoc(null)}
            title={`文档块预览：${previewDoc?.title ?? '未命名文档'}`}
            width={880}
            footer={null}
          >
            <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
              共 {previewChunks.length} 个父块（按 chunk_index 升序，content 为父块全文，含标题前缀）
            </Typography.Paragraph>
            <div className="kb-preview-list">
              {previewLoading ? (
                <div className="kb-preview-loading">
                  <Spin /> 加载中…
                </div>
              ) : (
                previewChunks.map(chunk => (
                  <div className="kb-chunk-card" key={chunk.id}>
                    <div className="kb-chunk-head">
                      <span className="kb-chunk-index">#{chunk.chunkIndex + 1}</span>
                      <span className="kb-chunk-title" title={chunk.title ?? ''}>
                        {chunk.title ?? '—'}
                      </span>
                      <span className="kb-chunk-chars">{formatNumber(chunk.charCount ?? 0)} 字符</span>
                    </div>
                    <pre className="kb-chunk-content">{chunk.content}</pre>
                  </div>
                ))
              )}
            </div>
          </Modal>

          {/* 新增文档弹窗（粘贴 / 上传） */}
          <Modal
            open={addOpen}
            title="新增文档"
            okText="入库"
            cancelText="取消"
            confirmLoading={submitting}
            okButtonProps={{
              disabled:
                submitting ||
                (addMode === 'paste' && !pasteContent.trim()) ||
                (addMode === 'upload' && !uploadFile),
            }}
            onOk={() => (addMode === 'paste' ? submitPaste() : submitUpload())}
            onCancel={closeAddDialog}
            width={640}
          >
            <Typography.Paragraph type="secondary">
              粘贴内容或上传文件入库；同步执行切分与向量化，大文件可能需要等待。
            </Typography.Paragraph>
            <Tabs
              activeKey={addMode}
              onChange={key => setAddMode(key as 'paste' | 'upload')}
              items={[
                {
                  key: 'paste',
                  label: '粘贴内容',
                  children: (
                    <div className="kb-add-form">
                      <div className="kb-add-field">
                        <span className="kb-field-label">标题（可选，缺省取一级标题）</span>
                        <Input
                          value={pasteTitle}
                          onChange={event => setPasteTitle(event.target.value)}
                          placeholder="如：记账规则配置说明"
                        />
                      </div>
                      <div className="kb-add-field">
                        <span className="kb-field-label">格式</span>
                        <Radio.Group
                          value={pasteFormat}
                          onChange={event => setPasteFormat(event.target.value as 'markdown' | 'text')}
                          optionType="button"
                          buttonStyle="solid"
                          options={[
                            { label: 'Markdown', value: 'markdown' },
                            { label: '纯文本', value: 'text' },
                          ]}
                        />
                      </div>
                      <Input.TextArea
                        rows={10}
                        value={pasteContent}
                        onChange={event => setPasteContent(event.target.value)}
                        placeholder={
                          pasteFormat === 'markdown'
                            ? '# 标题\n\n正文（支持 Markdown 语法，按标题章节切分）…'
                            : '正文（纯文本，整篇作为一个章节切分）…'
                        }
                      />
                    </div>
                  ),
                },
                {
                  key: 'upload',
                  label: '上传文件',
                  children: (
                    <div className="kb-add-form">
                      <Upload.Dragger
                        accept={ACCEPT_EXTENSIONS}
                        maxCount={1}
                        beforeUpload={file => {
                          if (file.size > UPLOAD_MAX_BYTES) {
                            message.error('文件大小不能超过 5MB');
                            return Upload.LIST_IGNORE;
                          }
                          setUploadFile(file);
                          return false;
                        }}
                        onChange={({ file }) => {
                          // 受控 fileList 需配 onChange：同步移除与文件选择状态
                          if (file.status === 'removed') {
                            setUploadFile(null);
                          } else if (file.originFileObj) {
                            setUploadFile(file.originFileObj);
                          }
                        }}
                        onRemove={() => setUploadFile(null)}
                        fileList={
                          uploadFile
                            ? [{ uid: 'kb-upload-file', name: uploadFile.name, size: uploadFile.size, status: 'done' }]
                            : []
                        }
                      >
                        <p className="ant-upload-drag-icon">
                          <InboxOutlined />
                        </p>
                        <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
                        <p className="ant-upload-hint">支持 {ACCEPT_EXTENSIONS}，≤ 5MB</p>
                      </Upload.Dragger>
                      <div className="kb-add-field">
                        <span className="kb-field-label">标题覆盖（可选）</span>
                        <Input
                          value={uploadTitle}
                          onChange={event => setUploadTitle(event.target.value)}
                          placeholder="缺省取文档内一级标题 → 文件名去后缀"
                        />
                      </div>
                    </div>
                  ),
                },
              ]}
            />
            {submitError ? (
              <Alert type="error" showIcon title={submitError} style={{ marginTop: 12 }} />
            ) : null}
          </Modal>
    </div>
  );
}
