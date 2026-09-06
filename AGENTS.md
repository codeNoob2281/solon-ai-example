# AGENTS.md

本文件为 AI 编码代理（Agent）在本仓库工作时需要遵守的约定与交付标准。

## 交付标准（重要）

- **编译通过即可交付**：以 `mvn clean install -DskipTests` 全量构建（父 POM 聚合的所有模块）通过为交付门槛。
- **不做复杂的运行时/端到端测试验证**：无需起 Milvus、Ollama、大模型等外部依赖做联调验证；也不要求补充单测来证明功能正确。
- 允许做**基础冒烟检查**（如确认 Spring 应用能正常启动到 `Started ...`），但不把端到端调用视为交付前提。
- 修改代码后必须保证编译通过；若引入了编译错误，需要修复后再交付。

```bash
mvn clean install -DskipTests
```

## 提交规范

- **提交信息使用英文**，采用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：`<type>(<scope>): <subject>`。
- 常用 type：`feat`（新功能）、`fix`（修复）、`docs`（文档）、`refactor`（重构）、`chore`（构建/杂项）、`test`（测试）；scope 为模块名（如 `rag-example`）可选。
- `<subject>` 用祈使句、首字母小写、简洁（一行不超过 72 字符）；需要补充细节时在 body 中用 `- ` 列表逐条说明。
- 提交示例：

```text
feat: add rag-example traditional RAG example (document loading/chunking/Milvus ingestion)

- Add rag-example module: DocumentLoader(Text/Markdown/Html) + TokenSizeTextSplitter chunking
- RagConfig wires EmbeddingModel/MilvusClientV2/MilvusRepository with @Lazy beans
```

## 模块结构

```
solon-ai-example/
├── pom.xml                 # 父 POM（聚合：llm-core / llm-chat-example / rag-example）
├── .env                    # 环境变量（项目根目录，gitignore，启动前由 EnvFileLoader 注入 System properties）
├── llm-core/               # Starter：Solon AI ChatModel 自动装配 + .env 加载器
├── llm-chat-example/       # 对话示例应用
└── rag-example/            # 传统 RAG 示例（父子分段：父块存 MySQL，子块存 Milvus）
```

## 关键约定

1. **新增模块**：需在父 POM `<modules>` 中加入，并保证 `mvn clean install -DskipTests` 全量通过。
2. **.env 配置**：由 `llm-core` 的 `EnvFileLoader` 在 `SpringApplication.run()` 之前把 `.env` 中的**全部键值对**注入 System properties（无需维护白名单），新增环境变量键无需改动加载器；敏感键（含 KEY/TOKEN/PASSWORD/SECRET）日志自动脱敏。application.yml 中出现的 `${KEY:默认值}` 占位符须**同步登记**到根目录 `.env` 与 `.env.example`（两文件保持同键：`.env` 填实际值、`.env.example` 填默认值作模板），否则缺键时静默回落 application.yml 默认值。现有键组：`SERVER_PORT`、`AI_*`（ChatModel）、`RAG_EMBEDDING_*`（嵌入）、`RAG_PARENT_CHUNK_SIZE` / `RAG_PARENT_MIN_CHARS` / `RAG_CHILD_CHUNK_SIZE` / `RAG_CHILD_MIN_CHARS`（父子分段）、`MYSQL_*`（数据源：`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`）、`MILVUS_*`（连接/Collection）、`RAG_INGEST_*`（并行入库线程池：`RAG_INGEST_CORE_POOL_SIZE` / `RAG_INGEST_MAX_POOL_SIZE` / `RAG_INGEST_QUEUE_CAPACITY` / `RAG_INGEST_KEEP_ALIVE_SECONDS` / `RAG_INGEST_THREAD_NAME_PREFIX` / `RAG_INGEST_ALLOW_CORE_THREAD_TIMEOUT`，对应 `RagProperties.Ingestion`）。
3. **Lombok**：DTO / 配置属性类使用 `@Data`；服务类使用构造器注入。
4. **RAG 延迟装配**：`MilvusClientV2` 构造会发起阻塞的 `Connect()` RPC，`MilvusChildRepository` 构造会初始化（建/校验）Collection，MySQL 表由 `KnowledgeBaseStore.ensureTables()` 首次使用时懒建。三者相关 Bean 及服务注入点均使用 `@Lazy`/懒初始化，使应用启动不依赖 Milvus 与 MySQL 在线，首次使用知识库时才建连/建集合/建表。
5. **RAG 技术栈（父子分段）**：Solon AI 4.0.6（`solon-ai` 聚合包 + `io.milvus:milvus-sdk-java` 2.6.3 + `solon-ai-load-markdown` / `solon-ai-load-html`；不再使用 `solon-ai-repo-milvus`——其 Collection 结构固定，无法扩展独立字段）。数据分布：父块存 MySQL（MyBatis-Plus，`kb_document` / `kb_chunk`），子块存 Milvus（自建 `MilvusChildRepository`，`parent_id` 独立标量字段 + TRIE 索引）；检索按「相似度阈值 + limit」取子块 → 子块映射父块 → 父块内容进 LLM 上下文；问答复用 `llm-core` 的 `ChatModel`。
6. **参考文档**：《rag - RAG 相关概念》https://solon.noear.org/article/932 、《solon-ai-repo-milvus》https://solon.noear.org/article/941 。
7. **父子分段已知取舍**：MySQL↔Milvus 双写无跨库原子性（Milvus 失败时文档行保留 status=FAILED）；同一文档重复入库会以新 id 累积（无按文档删除）；MySQL 表结构以 `rag-example/src/main/resources/sql/knowledge_base.sql` 为准，改动 DDL 须同步 `KnowledgeBaseStore` 内的建表语句。
