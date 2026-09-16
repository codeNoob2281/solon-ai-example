# agent-example

Agent 示例应用。除原有 SimpleAgent / ReActAgent 示例外，新增 **Web 流式对话页**：

- 后端：`solon-ai-ui-aisdk`（Solon AI UI 协议适配，Central 已发布）将 ReActAgent 事件流
  包装为 **Vercel AI SDK UI Message Stream Protocol v1** 的 SSE；
- 前端：React 19 + `@ai-sdk/react`（useChat），源码在本模块 `frontend/` 下，
  构建产物输出到 `src/main/resources/static`（已提交，jar 开箱即用）。

## 流式对话链路

```
@ai-sdk/react useChat（DefaultChatTransport）
  → POST /api/agent/chat/stream（消息历史 JSON）
  → AgentChatController：取最后一条用户消息
  → chatAgent.prompt(text).session(session).stream()   # Flux<AgentEvent>（ReActAgent 多轮会话）
  → AiSdkStreamWrapper.toAiSdkAgentStream(...)          # → AI SDK 协议事件流
  → SSE（x-vercel-ai-ui-message-stream: v1）逐帧回传 → 前端逐字渲染
```

要点：

- 响应头 `x-vercel-ai-ui-message-stream: v1` 为 AI SDK 客户端识别协议的必要头；
- 多轮上下文按请求体 `id`（前端 useChat 的 chatId）维持 `InMemoryAgentSession`（进程内）；
- Wrapper 产出的 `SseEvent` 只暴露 `toString()`（`data:<payload>\n`），控制器剥掉帧前缀后
  由 `SseEmitter` 按标准 SSE 重新组帧（send 时显式 `text/plain;charset=UTF-8`，避免默认
  ISO-8859-1 写出中文乱码）；
- **必须用 `SseEmitter` + 自主订阅，不能直接返回 `Flux`**：ReAct 循环是阻塞执行，会占住
  订阅线程整个生成期；返回 Flux 时 Spring 的 `SseEmitterSubscriber` 按 request(1) 逐个拉取，
  其请求信号经 `subscribeOn` 调度后会排在被阻塞的线程后面，事件全部积压到生成结束才一次性
  刷出（前端无逐字效果，已实测踩坑）。自主订阅 request(MAX)，事件到达即 `emitter.send`
  （内部逐帧 write + flush）；
- 阻塞的 ReAct 循环用 `subscribeOn(boundedElastic)` 移出 Tomcat 工作线程；
  客户端断开（或点「停止」）→ `onCompletion/onTimeout` → dispose → 中断 Agent 执行。

## 页面访问

应用启动后（默认端口 8082）：

- 对话页：<http://localhost:8082/>（index.html）
- 流式端点：`POST /api/agent/chat/stream`（AI SDK 协议，供 useChat 使用）
- Swagger：<http://localhost:8082/swagger-ui.html>

## 前端开发与构建

前置：Node ≥ 22（本机 `node` 已是 v24）。

```bash
cd agent-example/frontend
npm install            # 安装依赖
npm run dev            # 开发模式（5173 端口，/api 代理到 http://localhost:8082）
npm run build          # 构建 → 产物输出到 ../src/main/resources/static（提交进 git）
```

- `npm run dev` 需同时启动后端（`mvn spring-boot:run -pl agent-example` 或运行 jar）；
- 重新构建前端后重新打包（`mvn -pl agent-example package`）即可让 jar 内页面更新。

## 后端对话 Agent

`ChatAgentConfig#chatAgent`：通用对话 ReActAgent（无工具、无输出 Schema，
sessionWindowSize(20) 维持多轮上下文）。要改成对接出行规划 `travelAgent` 等已有
Agent，只需在 `AgentChatController` 里替换注入的 Bean（注意：travelAgent 的输出
契约是结构化 JSON，流式页将原样显示 JSON 文本）。

AI 模型配置复用 `llm-core` 的 ChatModel（`.env` 中 `AI_*` 键组），无新增环境变量。
