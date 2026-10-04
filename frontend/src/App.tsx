import { useEffect, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { Button, ConfigProvider, Layout, Menu } from 'antd';
import type { MenuProps, ThemeConfig } from 'antd';
import {
  BookOutlined,
  CommentOutlined,
  CompassOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import ChatDemoPage from './pages/ChatDemoPage';
import TravelDemoPage from './pages/TravelDemoPage';
import RagChatPage from './pages/RagChatPage';
import KnowledgeBasePage from './pages/KnowledgeBasePage';

/**
 * 演示页外壳：左侧 antd 菜单（Layout.Sider + Menu，浅色主题、可折叠）+ 右侧页面。
 *
 * 路由用 hash 实现（#/chat / #/travel / #/rag/chat / #/rag/kb），刷新后保持当前页，
 * 不引入路由依赖。切换即卸载当前页（进行中的流式请求随之断开，后端会停止对应执行）。
 *
 * 菜单说明：Menu 的 inline 折叠态由 Sider 的 siderCollapsed 上下文驱动（inlineCollapsed
 * 显式传入以保持一致），两级分组用 SubMenu 承载——折叠后分组图标悬浮弹出子项；
 * 图标统一取 @ant-design/icons（不再用 emoji），折叠后由 item.title 提供悬浮提示。
 */
type PageKey = 'chat' | 'travel' | 'ragChat' | 'ragKb';

interface PageItem {
  key: PageKey;
  hash: string;
  icon: ReactNode;
  title: string;
  desc: string;
}

interface MenuGroup {
  key: string;
  label: string;
  icon: ReactNode;
  items: PageItem[];
}

const MENU_GROUPS: MenuGroup[] = [
  {
    key: 'agent',
    label: 'Agent 演示',
    icon: <RobotOutlined />,
    items: [
      {
        key: 'chat',
        hash: '#/chat',
        icon: <MessageOutlined />,
        title: '流式对话',
        desc: 'AI SDK 协议 SSE × useChat',
      },
      {
        key: 'travel',
        hash: '#/travel',
        icon: <CompassOutlined />,
        title: '出行规划工作台',
        desc: 'ReActAgent 工作过程可视化',
      },
    ],
  },
  {
    key: 'rag',
    label: 'RAG 知识库',
    icon: <BookOutlined />,
    items: [
      {
        key: 'ragChat',
        hash: '#/rag/chat',
        icon: <CommentOutlined />,
        title: 'RAG 问答',
        desc: '父子分段检索 × 引用来源',
      },
      {
        key: 'ragKb',
        hash: '#/rag/kb',
        icon: <DatabaseOutlined />,
        title: '知识库管理',
        desc: '文档列表 / 预览 / 重建 / 新增',
      },
    ],
  },
];

const ALL_PAGES: PageItem[] = MENU_GROUPS.flatMap(group => group.items);

const SIDEBAR_WIDTH = 232;
const SIDEBAR_COLLAPSED_WIDTH = 64;

/** 侧栏内容排布：品牌 + 菜单（超高时自身滚动）+ 底部（版本号 + 折叠按钮） */
const SIDEBAR_BODY_STYLE: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  padding: '14px 8px',
};

/** 主题对齐站点主色（--primary #4f6ef7）；两行菜单项需要比默认 40px 更高的行高 */
const APP_THEME: ThemeConfig = {
  token: {
    colorPrimary: '#4f6ef7',
    borderRadius: 8,
  },
  components: {
    Menu: {
      itemHeight: 48,
      itemMarginBlock: 4,
      itemPaddingInline: 12,
      itemBorderRadius: 10,
      // 去掉选中项右侧的竖条，选中态只用圆角底色表达
      activeBarWidth: 0,
    },
  },
};

/** 菜单项文案：标题 + 描述两行；折叠态由 antd 依据 item.title 显示悬浮提示 */
function MenuItemLabel({ title, desc }: { title: string; desc: string }) {
  return (
    <span className="sidebar-item-label">
      <span className="sidebar-item-title">{title}</span>
      <span className="sidebar-item-desc">{desc}</span>
    </span>
  );
}

const MENU_ITEMS: MenuProps['items'] = MENU_GROUPS.map(group => ({
  key: group.key,
  icon: group.icon,
  label: group.label,
  // 折叠态弹出的二级菜单挂在 body 上，需要独立类名承接排版（见 index.css .app-menu-popup）
  popupClassName: 'app-menu-popup',
  children: group.items.map(item => ({
    key: item.key,
    icon: item.icon,
    title: item.title,
    label: <MenuItemLabel title={item.title} desc={item.desc} />,
  })),
}));

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
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    const onHashChange = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const nav = (key: string) => {
    // 经由 hash 触发 hashchange，保证地址栏与状态一致（含手动改 URL 的场景）
    window.location.hash = ALL_PAGES.find(p => p.key === key)?.hash ?? '#/chat';
  };

  return (
    <ConfigProvider locale={zhCN} theme={APP_THEME}>
      <div className="app-shell">
        <Layout.Sider
          className="app-sidebar"
          theme="light"
          width={SIDEBAR_WIDTH}
          collapsedWidth={SIDEBAR_COLLAPSED_WIDTH}
          collapsed={collapsed}
          // 侧栏内部排布走语义 styles（内联样式），不受 antd 样式注入顺序影响
          styles={{ body: SIDEBAR_BODY_STYLE }}
        >
          <div className="brand">
            <div className="brand-logo">Solon</div>
            {!collapsed && <div className="brand-name">AI Demos</div>}
          </div>
          <Menu
            className="app-menu"
            mode="inline"
            theme="light"
            items={MENU_ITEMS}
            selectedKeys={[page]}
            defaultOpenKeys={MENU_GROUPS.map(group => group.key)}
            inlineCollapsed={collapsed}
            onClick={({ key }) => nav(key)}
            // 侧栏自带右边框，去掉 Menu inline 态的默认右边框避免双线
            style={{ borderInlineEnd: 'none' }}
          />
          <div className="sidebar-foot">
            {!collapsed && <span className="sidebar-foot-text">solon-ai 4.1.0 · RAG × Agent</span>}
            <Button
              type="text"
              size="small"
              title={collapsed ? '展开菜单' : '收起菜单'}
              aria-label={collapsed ? '展开菜单' : '收起菜单'}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(value => !value)}
            />
          </div>
        </Layout.Sider>
        <main className="page-main">
          {page === 'travel' && <TravelDemoPage />}
          {page === 'ragChat' && <RagChatPage />}
          {page === 'ragKb' && <KnowledgeBasePage />}
          {page === 'chat' && <ChatDemoPage />}
        </main>
      </div>
    </ConfigProvider>
  );
}
