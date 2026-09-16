import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 构建产物直接输出到 Spring Boot 静态资源目录（agent-example 打包后开箱即用）
// dev 模式：/api 代理到本机 agent-example（默认端口 8082），避免 CORS
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
