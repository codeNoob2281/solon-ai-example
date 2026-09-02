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
└── rag-example/            # 传统 RAG 示例（文档加载 → 分块 → Milvus 入库）
```

## 关键约定

1. **新增模块**：需在父 POM `<modules>` 中加入，并保证 `mvn clean install -DskipTests` 全量通过。
2. **.env 配置**：由 `llm-core` 的 `EnvFileLoader` 在 `SpringApplication.run()` 之前把 `.env` 中的**全部键值对**注入 System properties（无需维护白名单），新增环境变量键无需改动加载器；敏感键（含 KEY/TOKEN/PASSWORD/SECRET）日志自动脱敏。
3. **Lombok**：DTO / 配置属性类使用 `@Data`；服务类使用构造器注入。
4. **RAG 延迟装配**：`MilvusClientV2` 构造会发起阻塞的 `Connect()` RPC，`MilvusRepository` 构造会初始化（建）Collection。二者 Bean 及服务注入点均使用 `@Lazy`，使应用启动不依赖 Milvus 在线，首次使用知识库时才建连/建集合。
5. **RAG 技术栈**：Solon AI 4.0.6（`solon-ai-repo-milvus` / `solon-ai-load-markdown` / `solon-ai-load-html`），嵌入默认 Ollama `bge-m3`（provider/standard=ollama，apiUrl 用完整 `/api/embed` 地址），检索走 `MilvusRepository`，问答复用 `llm-core` 的 `ChatModel` + `ChatMessage.ofUserAugment`。
6. **参考文档**：《rag - RAG 相关概念》https://solon.noear.org/article/932 、《solon-ai-repo-milvus》https://solon.noear.org/article/941 。
