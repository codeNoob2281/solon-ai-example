# AGENTS.md

本文件为 AI 编码代理（Agent）在本仓库工作时需要遵守的约定与交付标准。

## 交付标准（重要）

- **编译/构建通过为最低交付门槛**；验证方式仅限 `mvn clean install -DskipTests` 或前端 `npm run build`。不得用“启动应用”来验证编译；若引入编译错误，必须修复后再交付。
- **最小验证**：纯前端改到无需验证后端，反之同理；
- **不做复杂的运行时/端到端测试验证**：无需起 Milvus、Ollama、大模型等外部依赖做联调验证；也不要求补充单测来证明功能正确。
- **默认不执行冒烟/启动验证**：基础冒烟（启动 Spring 到 `Started ...`、健康检查、接口调用、起 Docker、起 Vite dev server 等）不是交付门槛，也不是默认验证动作。只有用户明确说“冒烟 / smoke / 启动验证 / 起服务”时才执行；执行前先说明命令和范围，得到确认后再跑。
- **小改动规则**：改注释、格式、日志文案、局部变量、私有函数局部重构时，只做编译/类型检查，不跑测试、不启动服务、不冒烟。
- **不确定时先问**：若认为需要冒烟，先询问并等待确认，不得直接执行。完成后只输出建议验证命令，不要自动执行。

```bash
mvn clean install -DskipTests                  # 后端/跨模块改动

cd frontend && npm run build                   # 纯前端改动（无需 mvn）
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
├── pom.xml                 # 父 POM（聚合：llm-core / llm-chat-example / rag-example / agent-example / ai-starter）
├── .env                    # 环境变量（项目根目录，gitignore，启动前由 EnvFileLoader 注入 System properties）
├── frontend/               # 统一前端（React19 + Vite，项目根目录：npm run dev 5173（/api 代理）/ npm run build 产物 dist（gitignore），前后端分离）
├── llm-core/               # Starter：Solon AI ChatModel 自动装配 + .env 加载器
├── llm-chat-example/       # 对话示例模块（纯库 jar：独立启动仅限 IDE main()，打包启动走 ai-starter）
├── rag-example/            # 传统 RAG 示例模块（父子分段：父块存 MySQL，子块存 Milvus；纯库 jar）
├── agent-example/          # Agent 示例模块（SimpleAgent 电商评论五维度情感分析 + ReActAgent 出行规划 + Web 演示页（左侧菜单切换）：AI SDK 协议流式对话 + 出行规划工作台（自定义过程事件协议 SSE：任务列表/思考链/工具调用/Markdown 回答可视化）；页面由根目录统一前端 frontend/ 承载；纯库 jar）
└── ai-starter/             # 统一启动入口（唯一可执行 jar）：引用三个示例模块自动装配，mvn clean install 后 java -jar ai-starter/target/ai-starter.jar
```

## 关键约定

1. **新增模块**：需在父 POM `<modules>` 中加入，并保证 `mvn clean install -DskipTests` 全量通过。
2. **.env 配置**：由 `llm-core` 的 `EnvFileLoader` 在 `SpringApplication.run()` 之前把 `.env` 中的**全部键值对**注入 System properties（无需维护白名单），新增环境变量键无需改动加载器；敏感键（含 KEY/TOKEN/PASSWORD/SECRET）日志自动脱敏。application.yml 中出现的 `${KEY:默认值}` 占位符须**同步登记**到根目录 `.env` 与 `.env.example`（两文件保持同键：`.env` 填实际值、`.env.example` 填默认值作模板），否则缺键时静默回落 application.yml 默认值。现有键组：`SERVER_PORT`、`AI_*`（ChatModel）、`RAG_EMBEDDING_*`（嵌入）、`RAG_PARENT_CHUNK_SIZE` / `RAG_PARENT_MIN_CHARS` / `RAG_CHILD_CHUNK_SIZE` / `RAG_CHILD_MIN_CHARS`（父子分段）、`RAG_SEARCH_STRATEGY`（检索策略标识，默认 `parent-child`，可选 `parent-child`/`mysql-fulltext`/`hybrid-rrf`）、`RAG_SEARCH_VECTOR_WEIGHT` / `RAG_SEARCH_KEYWORD_WEIGHT` / `RAG_SEARCH_CANDIDATE_MULTIPLIER`（hybrid-rrf 参数：向量权重默认 0.6、关键词权重默认 0.4、候选倍数默认 3）、`MYSQL_*`（数据源：`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`）、`MILVUS_*`（连接/Collection）、`RAG_INGEST_*`（并行入库线程池：`RAG_INGEST_CORE_POOL_SIZE` / `RAG_INGEST_MAX_POOL_SIZE` / `RAG_INGEST_QUEUE_CAPACITY` / `RAG_INGEST_KEEP_ALIVE_SECONDS` / `RAG_INGEST_THREAD_NAME_PREFIX` / `RAG_INGEST_ALLOW_CORE_THREAD_TIMEOUT`，对应 `RagProperties.Ingestion`）。
3. **Lombok 与依赖注入**：DTO / 配置属性类使用 `@Data`；服务类（`@Service`/`@Component`/`@RestController`）默认使用 `@Resource` 字段注入（`javax.annotation.Resource`，Boot 2.7 提供；同类型多 Bean 用 `@Resource(name = "beanName")` 指定）；**强依赖组件仍使用构造器注入**——构造期即需依赖完成初始化/校验逻辑的类（如 `RagRetrievalStrategyResolver` 注册表构建、`RagSearchService` 启动期策略选定、`MilvusChildRepository` 构造建/校验集合）保持构造器注入，禁止用 `@PostConstruct` 承接构造期逻辑。`@Resource` 字段名无对应 Bean 时按类型回退解析，`@Lazy` 标注在字段上仍然生效（懒代理）。
4. **RAG 延迟装配**：`MilvusClientV2` 构造会发起阻塞的 `Connect()` RPC，`MilvusChildRepository` 构造会初始化（建/校验）Collection，MySQL 表由 `KnowledgeBaseStore.ensureTables()` 首次使用时懒建。三者相关 Bean 及服务注入点均使用 `@Lazy`/懒初始化，使应用启动不依赖 Milvus 与 MySQL 在线，首次使用知识库时才建连/建集合/建表。
5. **RAG 技术栈（父子分段）**：Solon AI 4.1.0（`solon-ai` 聚合包 + `io.milvus:milvus-sdk-java` 2.6.3 + `solon-ai-load-markdown` / `solon-ai-load-html` + `solon-ai-ui-aisdk`（流式协议包装，版本由父 POM dependencyManagement 管理）；不再使用 `solon-ai-repo-milvus`——其 Collection 结构固定，无法扩展独立字段）。数据分布：父块存 MySQL（MyBatis-Plus，`kb_document` / `kb_chunk`），子块存 Milvus（自建 `MilvusChildRepository`，`parent_id` 独立标量字段 + TRIE 索引；支持按 parent_id 过滤删除与读向量维度）；检索按「相似度阈值 + limit」取子块 → 子块映射父块 → 父块内容进 LLM 上下文（该实现抽离为可配置策略接口 `RagRetrievalStrategy`，`com.example.ai.rag.retrieval` 包，默认 `parent-child`，经 `ai.rag.search.strategy` 选择；内置实现 `parent-child`、`mysql-fulltext`（后者为 MySQL ngram 全文检索父块，相关度分值 SQL 内 ROUND 保留 4 位，仅命中 INGESTED 文档）与 `hybrid-rrf`（向量 + 关键词 RRF 加权融合，RRF_K=60，权重 `ai.rag.search.vector-weight`/`keyword-weight` 默认 0.6/0.4，候选倍数 `candidate-multiplier` 默认 3，单端故障降级）；问答复用 `llm-core` 的 `ChatModel`。入库为 `SourceDocument` 流水线——**一个文件 / 一次粘贴 / 一个 URL = 一条 `kb_document` 记录**：markdown 经 `MarkdownLoader` 章节切分后**逐章节**父块切分（`kb_chunk.title` 存章节标题路径、`chunk_index` 文档内全局连续），`kb_document.content` 存原始全文（重建索引依据）、`doc_type` 存文档格式（markdown/html/text）；标题按「用户填写 → 文档内一级标题（`MarkdownTitleExtractor`）→ 文件名/兜底"未命名文档"」解析。知识库管理接口挂 `/api/rag/kb/*`（汇总 / 文档分页（依赖 `MybatisPlusConfig` 分页插件）/ 块预览 / 重建 / 删除（Milvus 子块尽力而为）/ 上传 / 粘贴，业务异常局部映射 400/409），RAG 流式问答 `POST /api/rag/chat/stream`（`AiSdkStreamWrapper.toAiSdkStream` 包装为 AI SDK UI Message Stream Protocol v1 SSE，引用来源随 metadata part 下发，提示词经 `RagPromptFactory` 与同步问答共用）；`/rag/*` 为保留调试接口。`/api` 前缀接口由统一前端 frontend/ 的「RAG 知识库」菜单组（RAG 问答 / 知识库管理两页）承载。
6. **参考文档**：《rag - RAG 相关概念》https://solon.noear.org/article/932 、《solon-ai-repo-milvus》https://solon.noear.org/article/941 。
7. **父子分段已知取舍**：MySQL↔Milvus 双写无跨库原子性（Milvus 失败时文档行保留 status=FAILED）；同一来源重复上传/粘贴不做去重拦截（以新 id 累积，按文档删除/重建索引由知识库管理接口提供，删除时 Milvus 子块尽力而为、失败仅告警不阻断）；MySQL 表结构以 `rag-example/src/main/resources/sql/knowledge_base.sql` 为准，改动 DDL 须同步 `KnowledgeBaseStore` 内的建表语句；`kb_chunk` 全文索引（ngram）由 `mysql-fulltext` 策略首次使用时懒建（`ensureFulltextIndex`，需 MySQL 5.7.6+，默认 parent-child 策略不依赖该索引）。
8. **统一启动入口 ai-starter**：三个示例模块（llm-chat-example / rag-example / agent-example）为纯库 jar（POM 不配 spring-boot-maven-plugin，独立启动仅限 IDE 运行 main()），其 Bean 经各自 `XxxExampleAutoConfiguration`（`META-INF/spring.factories` 注册，机制同 llm-core）自动装配进 `ai-starter`（自动配置类以 `@ComponentScan` 模块自身包路径并排除本模块 Application 类与 `OpenApiConfig`；三处同名 `@Bean openAPI()` 会冲突，合并启动由 ai-starter 的 `OpenApiConfig` 统一提供）。`@MapperScan("com.example.ai.rag.mapper")` 由 `RagExampleAutoConfiguration` 携带（独立启动时与 RagExampleApplication 上的声明重复注册，Spring 按同类去重）。合并版 application.yml 在 ai-starter（fat jar 内 BOOT-INF/classes 优先于依赖 jar，模块各自 yml 仅独立启动时生效）；新增 `${KEY}` 占位符仍按约定 2 登记 `.env` / `.env.example`。
