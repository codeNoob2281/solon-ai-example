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
├── llm-core/                       # Starter：Solon AI 自动装配
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/ai/core/
│       │   ├── ChatModelAutoConfiguration.java   # 自动装配入口
│       │   ├── ChatModelProperties.java          # @ConfigurationProperties
│       │   └── EnvFileLoader.java                # .env 加载器
│       └── resources/META-INF/spring.factories   # 自动装配注册
├── llm-chat-example/                # 对话示例应用
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/ai/example/
│       │   ├── AiChatApplication.java             # 入口（启动前加载 .env）
│       │   ├── controller/ChatController.java     # POST /chat/* 系列接口
│       │   ├── service/ChatService.java           # AI 对话服务（含流式 + 自定义智能体）
│       │   └── dto/                               # 请求体
│       └── resources/application.yml
└── rag-example/                    # 传统 RAG 示例应用（父子分段：父块存 MySQL，子块存 Milvus）
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
        │   │   └── KbChunk.java                   # 父块表 kb_chunk
        │   ├── mapper/
        │   │   ├── KbDocumentMapper.java          # MyBatis-Plus BaseMapper
        │   │   └── KbChunkMapper.java
        │   ├── storage/
        │   │   └── KnowledgeBaseStore.java        # MySQL 存取 + 懒建表
        │   ├── service/
        │   │   ├── RagIngestionService.java       # 文档加载 + 父/子分段 + MySQL/Milvus 入库
        │   │   └── RagSearchService.java          # 子块检索 → 父块映射 → RAG 问答
        │   ├── controller/RagController.java      # POST /rag/ingest/* + GET /rag/search + POST /rag/ask
        │   └── dto/                               # 请求/响应体
        └── resources/
            ├── application.yml
            ├── sql/knowledge_base.sql             # MySQL 表结构（与懒建表语句一致）
            └── docs/                              # 示例知识文档（sample.md/txt/html）
```

## 构建

> Maven settings.xml 已配置在 `C:\Users\ZJBR\.m2\settings.xml`，本地仓库指向 `D:\apache-maven-3.6\maven-repository`，fingard profile 自动激活，无需额外 `-Dmaven.repo.local` 参数。

```bash
# 根目录构建（同时构建 llm-core 与 llm-chat-example）
mvn clean install -DskipTests

# 运行示例
java -jar llm-chat-example/target/solon-ai-example.jar
```

## 配置

编辑项目根目录 `.env` 文件（参考 `.env.example`）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AI_API_URL` | `http://localhost:1234/v1` | OpenAI 兼容 API 地址（不要带 `/v1/chat/completions`） |
| `AI_API_KEY` | `sk-your-api-key` | API Key |
| `AI_MODEL` | `gpt-3.5-turbo` | 模型名称 |
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

### 流式对话（SSE）

```bash
curl -N -X POST http://localhost:8080/chat/sendMessageStream \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍自己"}'
```

响应为 `text/event-stream`，逐块推送：

```
event: message
data: 你好！

event: message
data: 我是 AI 助手

event: done
data:
```

SSE 事件说明：
- `message`: 每个增量文本块（`data` 为该块内容）
- `done`: 流结束
- `error`: 调用出错

流式原理：`ChatService.stream()` 返回 Solon AI 的 `Flux<ChatResponse>`（Reactor），控制器订阅后通过 Spring MVC 的 `SseEmitter` 推送给客户端，无需引入 WebFlux。

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

---

## RAG 示例（rag-example）

参考官方文档：
- [《rag - RAG 相关概念》](https://solon.noear.org/article/932)
- [《solon-ai-repo-milvus》](https://solon.noear.org/article/941)

**当前阶段已实现：父子分段 RAG——文档加载（DocumentLoader）→ 父块切分（存 MySQL 分块表）→ 子块切分（向量化存 Milvus，`parent_id` 独立字段关联父块）；检索为「子块检索 → 父块映射 → 父块进 LLM 上下文」（Small-to-Big）。**

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
# 根目录构建（同时构建三个模块）
mvn clean install -DskipTests

# 运行 RAG 示例（默认端口 8081）
java -jar rag-example/target/rag-example.jar
```

### API

```bash
# 1. 原始文本入库（加载 → 父块存 MySQL → 子块存 Milvus）
curl -X POST http://localhost:8081/rag/ingest/text \
  -H "Content-Type: application/json" \
  -d '{"text":"RAG 是检索增强生成的简称……","title":"RAG 概念"}'

# 2. 服务器单文件入库（自动按扩展名选 Markdown/Html/Text 加载器）
curl -X POST http://localhost:8081/rag/ingest/file \
  -H "Content-Type: application/json" \
  -d '{"path":"rag-example/src/main/resources/docs/sample.md"}'

# 3. 目录批量入库（扫描 .md/.markdown/.html/.htm/.txt）
curl -X POST http://localhost:8081/rag/ingest/directory \
  -H "Content-Type: application/json" \
  -d '{"path":"rag-example/src/main/resources/docs"}'

# 4. 子块向量检索（返回映射后的父块，验证入库效果）
curl "http://localhost:8081/rag/search?q=什么是RAG&limit=4"

# 5. RAG 问答（检索增强生成）
curl -X POST http://localhost:8081/rag/ask \
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

1. **子块检索**：`MilvusChildRepository.search()` 按「相似度阈值 + limit」向量检索子块（`SimilarityUtil.refilter` 阈值二次过滤）
2. **父块映射**：子块按 `metadata.parent_id` 分组（同父块取最高子块相似度），MySQL `selectBatchIds` 批量取父块行（父块行缺失时 WARN 跳过）
3. **上下文组装**：父块内容按最高子块相似度倒序进入 LLM 上下文（`/rag/search` 返回映射后的父块，`/rag/ask` 用于问答）

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
