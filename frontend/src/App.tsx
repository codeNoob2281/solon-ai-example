import { useEffect, useState } from 'react';
import ChatDemoPage from './pages/ChatDemoPage';
import TravelDemoPage from './pages/TravelDemoPage';
import RagChatPage from './pages/RagChatPage';
import KnowledgeBasePage from './pages/KnowledgeBasePage';

/**
 * 演示页外壳：左侧菜单切换不同演示界面（分组菜单）。
 *
 * 路由用 hash 实现（#/chat / #/travel / #/rag/chat / #/rag/kb），刷新后保持当前页，
 * 不引入路由依赖。切换即卸载当前页（进行中的流式请求随之断开，后端会停止对应执行）。
 */
type PageKey = 'chat' | 'travel' | 'ragChat' | 'ragKb';

interface PageItem {
  key: PageKey;
  hash: string;
  icon: string;
  title: string;
  desc: string;
}

interface MenuGroup {
  label: string;
  items: PageItem[];
}

const MENU_GROUPS: MenuGroup[] = [
  {
    label: 'Agent 演示',
    items: [
      {
        key: 'chat',
        hash: '#/chat',
        icon: '💬',
        title: '流式对话',
        desc: 'AI SDK 协议 SSE × useChat',
      },
      {
        key: 'travel',
        hash: '#/travel',
        icon: '🧭',
        title: '出行规划工作台',
        desc: 'ReActAgent 工作过程可视化',
      },
    ],
  },
  {
    label: 'RAG 知识库',
    items: [
      {
        key: 'ragChat',
        hash: '#/rag/chat',
        icon: '📚',
        title: 'RAG 问答',
        desc: '父子分段检索 × 引用来源',
      },
      {
        key: 'ragKb',
        hash: '#/rag/kb',
        icon: '🗂️',
        title: '知识库管理',
        desc: '文档列表 / 预览 / 重建 / 新增',
      },
    ],
  },
];

const ALL_PAGES: PageItem[] = MENU_GROUPS.flatMap(group => group.items);

function pageFromHash(): PageKey {
  switch (window.location.hash) {
    case '#/travel':
      return 'travel';
    case '#/rag/chat':
      return 'ragChat';
    case '#/rag/kb':
      return 'ragKb';
    default:
      return 'chat';
  }
}

export default function App() {
  const [page, setPage] = useState<PageKey>(pageFromHash);

  useEffect(() => {
    const onHashChange = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const nav = (key: PageKey) => {
    // 经由 hash 触发 hashchange，保证地址栏与状态一致（含手动改 URL 的场景）
    window.location.hash = ALL_PAGES.find(p => p.key === key)?.hash ?? '#/chat';
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo">Solon</div>
          <div className="brand-name">AI Demos</div>
        </div>
        <nav className="nav">
          {MENU_GROUPS.map(group => (
            <div className="nav-group" key={group.label}>
              <div className="nav-group-label">{group.label}</div>
              {group.items.map(item => (
                <button
                  key={item.key}
                  className={`nav-item ${page === item.key ? 'active' : ''}`}
                  onClick={() => nav(item.key)}
                >
                  <span className="nav-icon">{item.icon}</span>
                  <span className="nav-text">
                    <span className="nav-title">{item.title}</span>
                    <span className="nav-desc">{item.desc}</span>
                  </span>
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">solon-ai 4.1.0 · RAG × Agent</div>
      </aside>
      <main className="page-main">
        {page === 'travel' && <TravelDemoPage />}
        {page === 'ragChat' && <RagChatPage />}
        {page === 'ragKb' && <KnowledgeBasePage />}
        {page === 'chat' && <ChatDemoPage />}
      </main>
    </div>
  );
}
