# agent-example

Agent 示例应用。除原有 SimpleAgent / ReActAgent 示例外，新增 **Web 演示页**（左侧菜单切换）：

- **流式对话**：后端 `solon-ai-ui-aisdk`（Solon AI UI 协议适配，Central 已发布）将
  ReActAgent 事件流包装为 **Vercel AI SDK UI Message Stream Protocol v1** 的 SSE；
  前端 React 19 + `@ai-sdk/react`（useChat）；
- **出行规划工作台**：ReActAgent（planningMode + 工具调用）工作过程可视化——任务列表、
  思考链、工具调用（入参/出参/耗时）、Markdown 最终回答；走**自定义过程事件协议** SSE
  （PlanEvent 的任务列表内容在 AI SDK 包装器中会丢失，故该页 1:1 透出 Agent 事件）；
  UI 基于 **Vercel AI Elements 组件库**（shadcn 注册表：Task/Reasoning/Tool/Message/
  Conversation/PromptInput，Tailwind CSS 4 + shadcn 令牌作用域于 `.ai-theme` 容器）。
  前端源码在项目根目录统一前端 `frontend/` 下（已从本模块迁出），构建产物输出 `frontend/dist`
  （前后端分离：gitignore 不入库，后端为纯 API jar，页面经 Vite dev server 或静态部署访问）。

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

## 出行规划工作台链路（自定义事件协议）

```
前端 fetch 流式读取（POST /api/agent/travel/stream，body: {id, message}）
  → TravelAgentStreamController：travelAgent.prompt(message).session(session).stream()
  → 逐 AgentEvent 映射为 JSON 事件帧（start/plan/text_delta/tool_start/tool_end/metrics/finish…）
  → SSE（data:<json>，终帧 [DONE]）→ 前端渲染任务列表 / 思考链 / 工具调用 / 最终回答 / 用量信息
```

事件帧契约详见 `TravelAgentStreamController` 类注释。要点：

- **不用 `AiSdkStreamWrapper`**：AI SDK 包装器将 Agent 特有事件降级为 data-* part 且仅反射
  `getText()`，`PlanEvent` 的 `getPlans()/getPlanStage()/getPlanIndex()` 会丢失，
  无法支撑任务列表可视化；
- SSE 管道与对话端点同源（SseEmitter + 自主订阅 + boundedElastic + 断开中断）；
- 多轮澄清按请求体 `id` 维持独立会话存储（与对话 demo 互不共享）；
- 前端 Markdown 渲染用 AI Elements `MessageResponse`（内置 Streamdown）；
- **用量信息**：运行结束（`RunEndEvent.getMetrics()`）出 `metrics` 帧，带本轮的输入/输出 token、
  缓存读取 token 与命中率（`Metrics.getCacheRate()`）、耗时，以及上下文占用（末次推理输入 token）
  与模型上下文窗口（`.env` 的 `AI_CHAT_CONTEXT_LENGTH`）；前端在输入框下方用 AI Elements
  `Context` 组件展示（摘要行 + 悬浮卡的分项明细与占用进度）。

## 页面访问（前后端分离）

- **后端**（统一入口 `ai-starter`，端口取 `.env` 的 `SERVER_PORT`，application.yml 兜底默认 8080）：
  - 对话流式端点：`POST /api/agent/chat/stream`（AI SDK 协议，供 useChat 使用）
  - 出行规划流式端点：`POST /api/agent/travel/stream`（自定义过程事件协议）
  - 出行规划（一次性）：`POST /agent/travel/plan`（自然语言结果）
  - Swagger：<http://localhost:8080/swagger-ui.html>
- **前端**（页面一律在前端工程侧，后端不内嵌页面）：
  - 开发：`cd frontend && npm run dev` → <http://localhost:5173/>（`/api` 已代理到后端端口）
  - 部署：`npm run build` 产物在 `frontend/dist`（gitignore），交给任意静态服务器，
    并把 `/api` 反向代理到后端

## 前端开发与构建

前置：Node ≥ 22（本机 `node` 已是 v24）。

```bash
cd frontend            # 项目根目录下的统一前端（已从 agent-example/frontend 迁出）
npm install            # 安装依赖
npm run dev            # 开发模式（5173 端口，/api 代理到 .env 的 SERVER_PORT，本机 8080）
npm run build          # 构建 → 产物输出到 dist/（gitignore 不入库）
```

- `npm run dev` 需同时启动后端（根目录构建后 `java -jar ai-starter/target/ai-starter.jar`；
  三个示例模块已转为纯库 jar，仅可 IDE 运行各自 main() 独立启动）；
- 后端 jar 为纯 API（pom 无任何前端内嵌逻辑），页面不进 jar。

## 后端对话 Agent

`ChatAgentConfig#chatAgent`：通用对话 ReActAgent（无工具、无输出 Schema，
sessionWindowSize(20) 维持多轮上下文）。要改成对接出行规划 `travelAgent` 等已有
Agent，只需在 `AgentChatController` 里替换注入的 Bean（travelAgent 以自然语言
回复、格式不做限制，可为 Markdown，流式页可直接展示）。

AI 模型配置复用 `llm-core` 的 ChatModel（`.env` 中 `AI_*` 键组），无新增环境变量。
