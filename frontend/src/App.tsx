import { useEffect, useState } from 'react';
import ChatDemoPage from './pages/ChatDemoPage';
import TravelDemoPage from './pages/TravelDemoPage';

/**
 * 演示页外壳：左侧菜单切换不同 Agent 演示界面。
 *
 * 路由用 hash 实现（#/chat / #/travel），刷新后保持当前页，不引入路由依赖。
 * 切换即卸载当前页（进行中的流式请求随之断开，后端会停止对应 Agent 执行）。
 */
type PageKey = 'chat' | 'travel';

const PAGES: Array<{ key: PageKey; hash: string; icon: string; title: string; desc: string }> = [
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
];

function pageFromHash(): PageKey {
  return window.location.hash === '#/travel' ? 'travel' : 'chat';
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
    window.location.hash = PAGES.find(p => p.key === key)?.hash ?? '#/chat';
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo">Solon</div>
          <div className="brand-name">AI Agent Demos</div>
        </div>
        <nav className="nav">
          {PAGES.map(item => (
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
        </nav>
        <div className="sidebar-foot">solon-ai 4.1.0 · ReActAgent</div>
      </aside>
      <main className="page-main">
        {page === 'travel' ? <TravelDemoPage /> : <ChatDemoPage />}
      </main>
    </div>
  );
}
