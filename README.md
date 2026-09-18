# Solon AI Example

本项目主要用于**探索 Spring Boot 2.x + Solon AI 实现 Java 语言的 Agent 项目落地实践**，提供一些简单的示例代码，方便快速上手和二次开发。

## 技术栈

- **Spring Boot**: 2.7.18
- **Solon AI**: 4.0.6 (`solon-ai-core` + `solon-ai-dialect-openai`)
- **Java**: 8
- **OpenAI 协议**: 兼容 `/v1/chat/completions`

## 探索方向

- 在 Spring Boot 生态中集成 Solon AI，构建可复用的 LLM Starter
- ChatModel 的自动装配与统一管理
- Agent 能力的逐步引入（Talent / Tool / ReAct 等）
- OpenAI 兼容协议的对接与方言自动注册

## 多模块结构

```
solon-ai-example/
├── pom.xml                         # 父 POM（聚合模块）
├── .env                            # 环境变量（项目根目录，不提交）
├── .env.example                    # 环境变量模板（提交）
├── frontend/                       # 统一前端（React 19 + Vite，项目根目录：npm run dev 5173（/api 代理）/ npm run build 产物 dist，前后端分离）
├── llm-core/                       # Starter：Solon AI 自动装配
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/ai/core/
│       │   ├── ChatModelAutoConfiguration.java   # 自动装配入口
│       │   ├── ChatModelProperties.java          # @ConfigurationProperties
│       │   └── EnvFileLoader.java                # .env 加载器
│       └── resources/META-INF/spring.factories   # 自动装配注册
├── llm-chat-example/                  # 对话示例模块（纯库 jar：独立启动仅限 IDE main()，打包启动走 ai-starter）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/ai/example/
│       │   ├── AiChatApplication.java             # 入口（启动前加载 .env）
│       │   ├── controller/ChatController.java     # POST /chat/* 系列接口
│       │   ├── service/ChatService.java           # AI 对话服务（含流式 + 自定义智能体）
│       │   └── dto/                               # 请求体
│       └── resources/application.yml
├── rag-example/                     # 传统 RAG 示例模块（父子分段：父块存 MySQL，子块存 Milvus；纯库 jar）
    ├── pom.xml
    └── src/main/
        ├── java/com/example/ai/rag/
        │   ├── RagExampleApplication.java         # 入口（启动前加载 .env，@MapperScan）
        │   ├── config/
        │   │   ├── RagProperties.java             # @ConfigurationProperties(ai.rag.*)
        │   │   ├── RagConfig.java                 # EmbeddingModel/MilvusClientV2/子块仓库/父-子分割器 Bean
        │   │   ├── RetryableEmbeddingModel.java   # 嵌入重试装饰器
        │   │   └── OpenApiConfig.java             # Swagger 配置
        │   ├── repository/
        │   │   └── MilvusChildRepository.java     # 子块向量仓库（自建集合：parent_id 独立字段）
        │   ├── entity/
        │   │   ├── KbDocument.java                # 文档表 kb_document
        │   │   ├── KbChunk.java                   # 父块表 kb_chunk
        │   │   └── KbChunkFullTextRow.java        # 全文检索命中行（查询投影，非表实体）
        │   ├── mapper/
        │   │   ├── KbDocumentMapper.java          # MyBatis-Plus BaseMapper
        │   │   └── KbChunkMapper.java
        │   ├── retrieval/
        │   │   ├── RagRetrievalStrategy.java       # 检索策略接口（可配置，默认 parent-child）
        │   │   ├── ParentChildRetrievalStrategy.java # 默认策略：子块检索 → 父块映射（Small-to-Big）
        │   │   ├── MysqlFullTextRetrievalStrategy.java # 关键词策略：MySQL FULLTEXT 全文检索父块
        │   │   ├── HybridRrfRetrievalStrategy.java # 混合策略：向量 + 关键词 RRF 加权融合
        │   │   └── RagRetrievalStrategyResolver.java # 按配置选择策略（启动时解析）
        │   ├── storage/
        │   │   └── KnowledgeBaseStore.java        # MySQL 存取 + 懒建表
        │   ├── service/
        │   │   ├── RagIngestionService.java       # 文档加载 + 父/子分段 + MySQL/Milvus 入库
        │   │   └── RagSearchService.java          # 检索编排（策略可配置）→ RAG 问答
        │   ├── controller/RagController.java      # POST /rag/ingest/* + GET /rag/search + POST /rag/ask
        │   └── dto/                               # 请求/响应体
        └── resources/
            ├── application.yml
            ├── sql/knowledge_base.sql             # MySQL 表结构（与懒建表语句一致）
            └── docs/                              # 示例知识文档（sample.md/txt/html）
├── agent-example/                   # Agent 示例模块（SimpleAgent 评论情感分析；纯库 jar）
    ├── pom.xml
    └── src/main/
        ├── java/com/example/ai/agent/
        │   ├── AgentExampleApplication.java          # 独立启动入口（IDE 运行；打包启动走 ai-starter）
        │   ├── config/
        │   │   ├── SentimentAgentConfig.java         # SimpleAgent Bean（角色/指令/输出 Schema/重试）
        │   │   ├── WeatherAgentConfig.java           # SimpleAgent Bean + FunctionTool（本地 mock 天气工具）
        │   │   └── OpenApiConfig.java                # Swagger 配置
        │   ├── controller/
        │   │   ├── SentimentAnalysisController.java  # POST /agent/sentiment/analyze
        │   │   └── WeatherAgentController.java       # POST /agent/weather/query
        │   ├── service/
        │   │   ├── CommentSentimentService.java      # 调用 SimpleAgent + 情感标签归一化
        │   │   └── WeatherAgentService.java          # 天气查询（Agent 自动调用 mock 工具）
        │   ├── tool/
        │   │   └── WeatherMockData.java              # 天气本地 Mock 数据源（确定性伪随机）
        │   └── dto/                                  # CommentInput / CommentSentimentResult / WeatherQueryInput / WeatherInfo
        └── resources/application.yml
└── ai-starter/                        # 统一启动入口（唯一可执行 jar）：依赖三个示例模块自动装配，合并后只需启动它
    ├── pom.xml
    └── src/main/
        ├── java/com/example/ai/starter/
        │   ├── AiStarterApplication.java            # 唯一 main（启动前加载 .env；三模块 Bean 经 spring.factories 自动装配）
        │   └── config/OpenApiConfig.java            # 合并版 Swagger 配置（替代三模块同名 OpenApiConfig，避免 openAPI bean 冲突）
        └── resources/application.yml                # 三模块配置合并版（fat jar 内优先于各模块自带 yml 生效）
```

## 构建

```bash
# 根目录全量构建（父 POM 聚合：llm-core / llm-chat-example / rag-example / agent-example / ai-starter）
mvn clean install -DskipTests

# 启动统一入口（合并对话 / RAG / Agent 全部接口，后端唯一可执行 jar）
java -jar ai-starter/target/ai-starter.jar
```

## 配置

编辑项目根目录 `.env` 文件（参考 `.env.example`）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AI_API_URL` | `http://localhost:1234/v1` | OpenAI 兼容 API 地址（不要带 `/v1/chat/completions`） |
| `AI_API_KEY` | `sk-your-api-key` | API Key |
| `AI_MODEL` | `gpt-3.5-turbo` | 模型名称 |
| `AI_CHAT_LOGGING_ENABLED` | `false` | 模型请求/响应日志开关（ChatModel 请求/响应 DEBUG 输出） |
| `SERVER_PORT` | `8080` | 服务端口 |

支持的 API 端点：
- OpenAI 官方: `https://api.openai.com/v1`
- Ollama: `http://localhost:11434/v1`
- LM Studio: `http://localhost:1234/v1`
- vLLM: `http://your-vllm-host:8000/v1`

## API 使用

### 非流式对话

```bash
curl -X POST http://localhost:8080/chat/sendMessage \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍自己"}'
```

响应：直接返回 AI 回复文本。

### 流式对话（SSE，含思考内容）

```bash
curl -N -X POST http://localhost:8080/chat/sendMessageStream \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍自己", "thinking": true}'
```

响应为 `text/event-stream`，逐块推送：

```
event: thinking
data: 用户想要一句简洁的自我介绍……

event: message
data: 你好！

event: message
data: 我是 AI 助手

event: done
data:
```

SSE 事件说明：
- `thinking`: 每个思考增量块（需模型支持思考；请求体 `thinking`：`true`=开启思考，`false`=关闭思考，不传=请求不携带该参数，由模型默认行为决定）
- `message`: 每个增量正文块（`data` 为该块内容）
- `done`: 流结束
- `error`: 调用出错

流式原理：`ChatService.stream()` 返回 Solon AI 4.1 的 `Flux<ChatEvent>`（Reactor），控制器将 `THINKING_DELTA` 投影为 `thinking` 事件、`TEXT_DELTA` 投影为 `message` 事件后，通过 Spring MVC 的 `SseEmitter` 推送给客户端，无需引入 WebFlux。

### 音频理解（识别动物叫声）

硬编码："请识别这段音频中是什么动物的叫声？" + 音频 URL，OpenAI 兼容方言会将 `AudioBlock` 序列化为 `audio_url` 内容块。

```bash
curl -X POST http://localhost:8080/chat/audioUnderstanding
```

对应 Solon AI 构建方式：

```java
ChatMessage msg = ChatMessage.ofUser("请识别这段音频中是什么动物的叫声？",
        AudioBlock.ofUrl("https://cdn.pixabay.com/download/audio/2026/01/24/audio_761a22043a.mp3?filename=dragon-studio-cute-cat-meow-472372.mp3"));
```

可选参数：`?modelName=xxx` 切换模型（默认 `dots3-note-prev`）。

### 视频理解（描述视频内容）

硬编码："请描述这段视频的内容？" + 视频 URL，OpenAI 兼容方言会将 `VideoBlock` 序列化为 `video_url` 内容块。

```bash
curl -X POST http://localhost:8080/chat/videoUnderstanding
# 可选：curl -X POST "http://localhost:8080/chat/videoUnderstanding?modelName=Qwen2.5-VL"
```

对应 Solon AI 构建方式：

```java
ChatMessage msg = ChatMessage.ofUser("请描述这段视频的内容？",
        VideoBlock.ofUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"));
```

### 自定义智能体（非流式）

模拟 agent 应用中的"自定义智能体"功能：传入**角色 + 系统指令 + 用户消息**，动态构建带 role / instruction 的 ChatModel。

```bash
curl -X POST http://localhost:8080/chat/customAgent \
  -H "Content-Type: application/json" \
  -d '{
    "role": "财务数据分析师",
    "instruction": "你负责分析订单与退款数据。金额单位均为元。",
    "message": "请分析本周订单退款率"
  }'
```

可选字段（不传则复用全局默认配置）：
- `apiUrl`: OpenAI 兼容 API 地址（可带 `/v1/chat/completions`，也可只到域名根）
- `apiKey`: API 密钥
- `model`: 模型名称

对应 Solon AI 构建方式：

```java
ChatModel agent = ChatModel.of("https://api.moark.com/v1/chat/completions")
        .apiKey("***")
        .model("Qwen3-32B")
        .role("财务数据分析师")
        .instruction("你负责分析订单与退款数据。金额单位均为元。")
        .build();
```

## 关键设计

1. **Starter 自动装配**: `llm-core` 通过 `META-INF/spring.factories` 注册 `ChatModelAutoConfiguration`，应用只需引入依赖即可获得 `ChatModel` Bean
2. **方言自动注册**: OpenAI 方言 (`OpenaiChatDialect`) 按类名自动注册，无需 Solon 容器
3. **apiUrl 自动拼接**: 配置 `api-url` 时不要带 `/v1/chat/completions`，Solon AI 会自动拼接
4. **.env 文件加载**: `EnvFileLoader` 在 `SpringApplication.run()` 之前读取项目根目录 `.env`，注入 `System.properties`，`application.yml` / `@ConfigurationProperties` 中的 `${...}` 占位符自动解析

## 依赖

- `llm-core`（starter）:
  - `spring-boot-autoconfigure`
  - `solon-ai-core` + `solon-ai-dialect-openai`
- `llm-chat-example`（示例应用）:
  - `llm-core`
  - `spring-boot-starter-web`
- `rag-example`（RAG 示例应用）:
  - `llm-core`
  - `spring-boot-starter-web`
  - `solon-ai`（聚合包：solon-ai-core + 各方言，含 openai/ollama EmbeddingDialect）
  - `milvus-sdk-java` 2.6.3（自建子块仓库 `MilvusChildRepository`，`parent_id` 独立字段）
  - `mybatis-plus-boot-starter` 3.5.5 + `mysql-connector-j`（MySQL 文档表/父块表）
  - `solon-ai-load-markdown` + `solon-ai-load-html`（文档加载器）
- `agent-example`（Agent 示例模块）:
  - `llm-core`
  - `spring-boot-starter-web`
  - `solon-ai-agent`（SimpleAgent 等；传递依赖 `solon-ai` 聚合包 + `solon-flow` + `solon-expression`）
  - `solon-ai-ui-aisdk`（Agent 事件流 → AI SDK UI Message Stream Protocol SSE）
- `ai-starter`（统一启动入口，唯一可执行 jar）:
  - `llm-chat-example` + `rag-example` + `agent-example`（三个示例模块；Bean 经各自 `META-INF/spring.factories` 注册的 `XxxExampleAutoConfiguration` 自动装配，模块 Application 类与 `OpenApiConfig` 被排除）
  - `spring-boot-starter-web`

---

## RAG 示例（rag-example）

参考官方文档：
- [《rag - RAG 相关概念》](https://solon.noear.org/article/932)
- [《solon-ai-repo-milvus》](https://solon.noear.org/article/941)

**当前阶段已实现：父子分段 RAG——文档加载（DocumentLoader）→ 父块切分（存 MySQL 分块表）→ 子块切分（向量化存 Milvus，`parent_id` 独立字段关联父块）；检索为「子块检索 → 父块映射 → 父块进 LLM 上下文」（Small-to-Big）。检索实现已抽离为可配置策略接口 `RagRetrievalStrategy`，内置 `parent-child`（默认，即上述父子分段检索）、`mysql-fulltext`（关键词全文检索）与 `hybrid-rrf`（向量 + 关键词 RRF 加权融合）三种实现。**

### 架构（父子分段数据分布）

| 层 | 存储 | 内容 |
|------|------|------|
| 文档 | MySQL `kb_document` | 文档信息（唯一标识 UUID、标题、来源、类型、父块数、状态） |
| 父块 | MySQL `kb_chunk` | 大粒度段落（文档 id + 区块 id + 内容 + 子块数），检索命中后进 LLM 上下文 |
| 子块 | Milvus 集合 | 小粒度文本片段（`id` / `parent_id` 独立标量字段 + TRIE 索引 / `content` / `metadata` / `embedding`），只做向量检索 |

> 说明：solon 内置 `MilvusRepository` 的 Collection 结构固定（无法扩展独立字段），
> 因此自建 `MilvusChildRepository` 直接基于 `MilvusClientV2` + `EmbeddingModel`
> 实现子块的建集合/写入/检索（批次嵌入与进度回调语义与其保持一致）。

### 运行前提

- 本机/局域网可访问 **Milvus**（本地 Standalone 默认 `http://localhost:19530`；或改 `MILVUS_URI` 指向远程）
- 可访问的 **MySQL**（默认 `jdbc:mysql://localhost:3306/rag_demo`，库不存在自动创建；表首次使用时懒建，DDL 见 `rag-example/src/main/resources/sql/knowledge_base.sql`）
- 可访问的**嵌入模型**服务，默认配置为 Ollama `bge-m3:latest`（`http://127.0.0.1:11434/api/embed`）
  - 若使用 OpenAI 兼容嵌入接口：将 `RAG_EMBEDDING_PROVIDER`/`RAG_EMBEDDING_STANDARD` 留空，`RAG_EMBEDDING_API_URL` 指向 `/v1/embeddings`
- RAG 问答（`/rag/ask`）复用 `AI_API_URL` 配置的 ChatModel

> **旧集合一次性迁移**：本项目之前的 Milvus 集合（`id/content/metadata/embedding` 四字段）
> 与新结构不兼容。首次使用时会校验集合结构并直接报错提示，删除旧集合
> （或更换 `MILVUS_COLLECTION` 指向新集合）后重新入库即可。

### 启动

```bash
# 根目录构建（全量构建五个模块）
mvn clean install -DskipTests

# 运行（统一入口 ai-starter；端口取根目录 .env 的 SERVER_PORT，默认 8080）
java -jar ai-starter/target/ai-starter.jar
```

### API

```bash
# 1. 原始文本入库（加载 → 父块存 MySQL → 子块存 Milvus）
curl -X POST http://localhost:8080/rag/ingest/text \
  -H "Content-Type: application/json" \
  -d '{"text":"RAG 是检索增强生成的简称……","title":"RAG 概念"}'

# 2. 服务器单文件入库（自动按扩展名选 Markdown/Html/Text 加载器）
curl -X POST http://localhost:8080/rag/ingest/file \
  -H "Content-Type: application/json" \
  -d '{"path":"rag-example/src/main/resources/docs/sample.md"}'

# 3. 目录批量入库（扫描 .md/.markdown/.html/.htm/.txt）
curl -X POST http://localhost:8080/rag/ingest/directory \
  -H "Content-Type: application/json" \
  -d '{"path":"rag-example/src/main/resources/docs"}'

# 4. 子块向量检索（返回映射后的父块，验证入库效果）
curl "http://localhost:8080/rag/search?q=什么是RAG&limit=4"

# 5. RAG 问答（检索增强生成）
curl -X POST http://localhost:8080/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"什么是 RAG？","limit":4}'
```

### 入库流程说明（父子分段）

1. **文档加载**：`RagIngestionService` 按扩展名选择 `MarkdownLoader` / `HtmlSimpleLoader` / `TextLoader`，并附加 `source` / `filename` / `title` 等元数据
2. **父块切分**：`parentSplitter`（`TokenSizeTextSplitter`，默认 1000 token）把文档切为大粒度父块，存 MySQL `kb_chunk`（含文档 id、区块 id、序号、内容、子块数）；文档信息存 `kb_document`（状态 INGESTING）
3. **子块切分**：每个父块再由 `childSplitter`（默认 200 token）切为小粒度子块，附加元数据 `document_id` / `parent_id` / `child_index`（子块继承文档元数据）
4. **子块入库**：`MilvusChildRepository.save()` 按 `batchSize` 分批 → `EmbeddingModel.embed()` 批量向量化 → `Upsert` 写入 Milvus（`parent_id` 提升为集合独立标量字段；首次使用自动建集合 + TRIE/向量索引），每完成一批通过 `progressCallback` 打印进度日志
5. **状态推进**：Milvus 写入成功 → 文档状态置 INGESTED；失败 → 尽力置 FAILED（MySQL 行保留待排查）

> **并行入库**：多文档时按"单线程处理最小维度 = 单个文档"提交到可配置线程池（`ai.rag.ingestion.*`），
> 一个文档的 父/子切分 → MySQL 落库 → 子块向量化 → 写入 Milvus 在同一根线程内完成，各文档并行处理以摊平 embedding 网络耗时；
> 任一文档失败会取消未开始的任务并抛出原始异常（与串行语义一致）。单文档直接在调用线程处理。
> 嵌入请求带超时与指数退避重试（`ai.rag.embedding.retry-*`）：接口超时/瞬断/限流时自动重试，
> 只重试失败的批次（入库与检索两条路径均生效）。

### 检索流程说明（Small-to-Big）

> **检索策略可配置**：检索实现抽离为策略接口 `RagRetrievalStrategy`（`com.example.ai.rag.retrieval` 包），
> `RagSearchService` 只做编排（策略检索 → 提示词组装 → LLM 生成）。
> 通过 `RAG_SEARCH_STRATEGY`（`ai.rag.search.strategy`）选择实现（标识 = 策略的 `name()`），
> 内置 `parent-child`（默认）/ `mysql-fulltext` / `hybrid-rrf`；扩展新策略只需实现接口并注册为 Spring Bean。
> 配置了未知标识会在启动时快速失败并列出可用策略。

以默认策略 `parent-child` 为例：

1. **子块检索**：`MilvusChildRepository.search()` 按「相似度阈值 + limit」向量检索子块（`SimilarityUtil.refilter` 阈值二次过滤）
2. **父块映射**：子块按 `metadata.parent_id` 分组（同父块取最高子块相似度），MySQL `selectBatchIds` 批量取父块行（父块行缺失时 WARN 跳过）
3. **上下文组装**：父块内容按最高子块相似度倒序进入 LLM 上下文（`/rag/search` 返回映射后的父块，`/rag/ask` 用于问答）

#### 关键词检索策略（`mysql-fulltext`，可选）

配置 `RAG_SEARCH_STRATEGY=mysql-fulltext` 切换，直接对 MySQL 父块表 `kb_chunk.content` 做
`MATCH ... AGAINST` 自然语言模式全文检索（ngram 中文分词，需 MySQL 5.7.6+）：

- 不依赖 Milvus 与向量模型，纯关键词匹配（无语义泛化）；检索粒度直接为父块
- 分值为 MySQL 全文相关度（非 0~1 相似度），SQL 内 `ROUND(..., 4)` 保留 4 位小数，按分值倒序；
  `similarityThreshold` 参数语义为「相关度下限」过滤
- 只命中已入库（`kb_document.status = INGESTED`）文档的父块，并 JOIN 带出标题/来源供结果展示
- FULLTEXT 索引 `idx_kb_chunk_content_ft(content)` 由该策略首次使用时懒建
  （`KnowledgeBaseStore.ensureFulltextIndex()`，默认 parent-child 策略不依赖该索引）；
  DDL 同步于 `rag-example/src/main/resources/sql/knowledge_base.sql`

#### 混合检索策略（`hybrid-rrf`，可选）

配置 `RAG_SEARCH_STRATEGY=hybrid-rrf` 切换，对向量检索（`parent-child`）与关键词检索
（`mysql-fulltext`）的结果做 **RRF（Reciprocal Rank Fusion）** 融合排序：

- 融合公式：`finalScore = vectorWeight × Σ 1/(RRF_K + rank) + keywordWeight × Σ 1/(RRF_K + rank)`，
  RRF_K 固定 60；权重由 `RAG_SEARCH_VECTOR_WEIGHT` / `RAG_SEARCH_KEYWORD_WEIGHT` 配置
  （默认 `0.6` / `0.4`，原始权重不归一化）
- 两来源各自检索 `limit × RAG_SEARCH_CANDIDATE_MULTIPLIER`（默认 3 倍）条候选，
  融合后取相关度前 limit；两来源均返回父块级命中，按父块 id 对齐，融合分 ROUND 保留 4 位小数
- `similarityThreshold` 原样透传（向量侧为相似度阈值，关键词侧为相关度下限）
- 任一来源检索失败（如 Milvus/MySQL 暂不可用）时降级：仅用另一来源结果，混合检索仍可用

### 关键配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MILVUS_URI` | `http://localhost:19530` | Milvus 地址 |
| `MILVUS_COLLECTION` | `solon_rag_demo` | 子块集合名（不能用 `-`） |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/rag_demo?...` | 数据源 URL（默认带 `createDatabaseIfNotExist=true`） |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / 空 | 数据源账号 |
| `RAG_PARENT_CHUNK_SIZE` | `1000` | 父块目标 token 数（存 MySQL，检索命中后进 LLM 上下文） |
| `RAG_PARENT_MIN_CHARS` | `600` | 父块句子边界回切最小字符数 |
| `RAG_CHILD_CHUNK_SIZE` | `200` | 子块目标 token 数（存 Milvus，向量检索） |
| `RAG_CHILD_MIN_CHARS` | `50` | 子块句子边界回切最小字符数 |
| `RAG_SEARCH_STRATEGY` | `parent-child` | 检索策略标识（`RagRetrievalStrategy#name()`）；可选 `parent-child`（默认，父子分段检索）/ `mysql-fulltext`（关键词全文检索）/ `hybrid-rrf`（向量 + 关键词 RRF 融合） |
| `RAG_SEARCH_VECTOR_WEIGHT` | `0.6` | 混合检索（`hybrid-rrf`）向量侧 RRF 权重（原始权重，不归一化） |
| `RAG_SEARCH_KEYWORD_WEIGHT` | `0.4` | 混合检索（`hybrid-rrf`）关键词侧 RRF 权重 |
| `RAG_SEARCH_CANDIDATE_MULTIPLIER` | `3` | 混合检索候选倍数：各来源候选条数 = limit × 倍数 |
| `RAG_EMBEDDING_API_URL` | `http://127.0.0.1:11434/api/embed` | 嵌入服务地址（Ollama 用完整地址） |
| `RAG_EMBEDDING_MODEL` | `bge-m3:latest` | 嵌入模型 |
| `RAG_EMBEDDING_PROVIDER` / `STANDARD` | `ollama` | 嵌入方言（空 = 默认 openai） |
| `RAG_EMBEDDING_TIMEOUT_SECONDS` | `60` | 嵌入请求超时秒数（框架默认 120） |
| `RAG_EMBEDDING_RETRY_MAX_ATTEMPTS` | `3` | 嵌入失败最大尝试次数（含首次，`1` = 不重试） |
| `RAG_EMBEDDING_RETRY_BACKOFF_MILLIS` | `1000` | 重试退避基数毫秒（按 2ⁿ 指数递增） |
| `RAG_INGEST_CORE_POOL_SIZE` | `4` | 并行入库核心线程数（参考嵌入服务并发能力） |
| `RAG_INGEST_MAX_POOL_SIZE` | `8` | 并行入库最大线程数 |
| `RAG_INGEST_QUEUE_CAPACITY` | `256` | 任务队列容量（`0` = SynchronousQueue 直接交接） |
| `RAG_INGEST_KEEP_ALIVE_SECONDS` | `60` | 空闲线程存活秒数 |
| `RAG_INGEST_THREAD_NAME_PREFIX` | `rag-ingest-` | 线程名前缀 |

> `MilvusChildRepository` Bean 标记为 `@Lazy`、MySQL 表首次使用时懒建：应用启动不要求 Milvus/MySQL 在线，
> 首次调用 `/rag/ingest/*` 或 `/rag/search` 时才建连/建集合/建表。
>
> **已知取舍**：MySQL↔Milvus 双写无跨库原子性；同一文档重复入库会以新 id 累积（文档删除/重建接口未实现）。

---

## Agent 示例（agent-example）

参考官方文档：
- [《agent 事件》](https://solon.noear.org/article/1496)

**基于 `solon-ai-agent` 的 `SimpleAgent`（@Preview API）实现电商用户评论五维度情感分析：
从商品属性（productAttributes）、商品质量（productQuality）、外观设计（appearanceDesign）、
使用体验（userExperience）、价格价值（costPerformance）五个维度输出情感标签
`POS`（正向）/ `NEG`（负向）/ `NTU`（中性）/ `UNKNOW`（无法分析）。**

### 实现要点

- `SentimentAgentConfig`：装配 `SimpleAgent` Bean——`SimpleAgent.of(chatModel)` 复用 llm-core 的
  `ChatModel` 自动装配；`.role()` + `.instruction()` 组装为「## 你的角色 + ## 执行指令」SystemPrompt；
  `.outputSchema(CommentSentimentResult.class)` 由 DTO 生成 JSON Schema 注入提示词并自动开启
  `response_format=json_object`（OpenAI 兼容方言）；`.modelOptions(temperature=0.1)` 降低分类随机性；
  `.retryConfig(3, 1000)` 失败指数退避重试；`.sessionWindowSize(0)` 无状态单次分析。
- `CommentSentimentService`：评论 DTO 序列化为 JSON 作为用户提示词 → `agent.prompt(...).call()`
  → `SimpleResponse.toBean(CommentSentimentResult.class)` 解析结构化结果（自动截取 JSON 片段，
  容错 markdown 围栏）→ 情感标签归一化（非法/缺失值兜底 `UNKNOW`）。
- 接口：`POST /agent/sentiment/analyze`，Swagger UI：http://localhost:8080/swagger-ui.html
  （实际端口以 `.env` 的 `SERVER_PORT` 为准，默认 8080）。

### 使用示例

```bash
# 构建后运行（统一入口 ai-starter，根目录 .env 注入配置）
java -jar ai-starter/target/ai-starter.jar

# 五维度情感分析
curl -X POST http://localhost:8080/agent/sentiment/analyze \
  -H "Content-Type: application/json" \
  -d '{"commentId":"c20260912001","content":"手机外观很漂亮，电池不太耐用，这个价格还算划算。"}'
```

响应示例：

```json
{
  "productAttributes": "UNKNOW",
  "productQuality": "NEG",
  "appearanceDesign": "POS",
  "userExperience": "NTU",
  "costPerformance": "POS"
}
```

### 工具调用示例（天气查询，本地 mock）

基于 `SimpleAgent` 的工具调用（Function Calling）：`WeatherAgentConfig` 用 `FunctionToolDesc`
编程式声明本地 mock 天气工具 `get_weather_by_city_and_date`（参数 city/date + `doHandle` 处理器，
数据由 `WeatherMockData` 以 `city#date` 哈希做确定性伪随机生成，**不走真实天气 API**），
经 `defaultToolAdd` 挂到 Agent；框架 `autoToolCall` 默认开启，模型自动发起工具调用，
工具结果回传后按指令约束汇总为 JSON 输出。

> 说明：该示例刻意不用 `outputSchema`（其触发 `response_format=json_object`，
> 与工具调用同开时部分 OpenAI 兼容服务会拒绝），改为指令约束 + `toBean` 容错解析。

```bash
curl -X POST http://localhost:8080/agent/weather/query \
  -H "Content-Type: application/json" \
  -d '{"city":"北京","date":"2026-09-14"}'
```

响应示例：

```json
{
  "weather": "多云",
  "minTemperature": 18,
  "maxTemperature": 26
}
```
