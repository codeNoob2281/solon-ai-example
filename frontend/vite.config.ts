import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// 前后端分离：本工程为纯前端（React 19 + Vite），构建产物输出到 dist（gitignore 不入库）
// dev 模式：/api 代理到本机 agent-example（.env 的 SERVER_PORT，本机 8080），避免 CORS
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      // AI Elements / shadcn 组件使用 "@/..." 别名导入
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    // 产物输出 dist（gitignore）：开发用 npm run dev；部署时把 dist 交给任意静态
    // 服务器并反向代理 /api 到后端（后端 jar 为纯 API，不内嵌页面）
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      // 与根目录 .env 的 SERVER_PORT 保持一致（本机 8080；application.yml 兜底默认 8082）
      '/api': 'http://localhost:8080',
    },
  },
});
