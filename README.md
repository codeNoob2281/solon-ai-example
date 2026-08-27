# Solon AI Chat Example

Spring Boot 2.7.18 + Solon AI 4.0.6 最小示例，使用 OpenAI 兼容协议进行 AI 对话。

## 技术栈

- **Spring Boot**: 2.7.18
- **Solon AI**: 4.0.6 (`solon-ai-core` + `solon-ai-dialect-openai`)
- **Java**: 8
- **OpenAI 协议**: POST `/v1/chat/completions`

## 项目结构

```
solon-ai-example/
├── .env                             # 环境变量（项目根目录，优先级高于 application.yml）
├── pom.xml
├── README.md
├── src/main/java/com/example/ai/
│   ├── AiChatApplication.java       # Spring Boot 入口（启动前加载 .env）
│   ├── config/
│   │   ├── ChatModelConfig.java     # 手工构建 ChatModel Bean
│   │   └── EnvFileLoader.java       # .env 文件加载器（JDK 原生，不依赖 spring-boot JAR）
│   ├── service/
│   │   └── ChatService.java         # AI 对话服务
│   ├── controller/
│   │   └── ChatController.java      # REST 接口 POST /chat/sendMessage
│   └── dto/
│       └── OpenAiRequest.java       # 请求体 {message: "..."}
└── src/main/resources/application.yml
```

## 运行

> Maven settings.xml 已配置在 `C:\Users\ZJBR\.m2\settings.xml`，本地仓库指向 `D:\apache-maven-3.6\maven-repository`，fingard profile 自动激活，无需额外 `-Dmaven.repo.local` 参数。

### 方式一：使用 .env 文件（推荐）

编辑项目根目录 `.env` 文件，然后直接运行：

```bash
# 1. 编辑 .env 设置你的 AI 服务地址
#    见下方 .env 配置说明

# 2. 编译 & 打包
mvn package -DskipTests

# 3. 运行（自动加载 .env）
java -jar target/solon-ai-example-1.0.0.jar
```

### 方式二：命令行环境变量

```bash
export AI_API_URL=http://localhost:1234/v1
export AI_API_KEY=sk-your-api-key
export AI_MODEL=gpt-3.5-turbo
java -jar target/solon-ai-example-1.0.0.jar
```

### .env 配置说明

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

```bash
curl -X POST http://localhost:8080/chat/sendMessage \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍自己"}'
```

响应：直接返回 AI 回复文本。

## 关键设计说明

1. **方言自动注册**: OpenAI 方言 (`OpenaiChatDialect`) 按类名自动注册，无需 Solon 容器
2. **apiUrl 自动拼接**: 配置 `api-url` 时不要带 `/v1/chat/completions`，Solon AI 会自动拼接
3. **手工构建 Bean**: 通过 `ChatModel.of(ChatConfig).build()` 手工创建 ChatModel
4. **.env 文件加载**: `EnvFileLoader` 在 `SpringApplication.run()` 之前读取项目根目录 `.env`，注入 `System.properties`，`application.yml` 中的 `${...}` 占位符自动解析。仅用 JDK 原生 API，不依赖 `spring-boot` JAR，兼容本机 Maven 仓库环境。

## 依赖说明

- `solon-ai-core`: 提供 ChatModel、ChatConfig、消息模型等核心抽象
- `solon-ai-dialect-openai`: 提供 OpenAI 协议方言（请求构建、响应解析、自动 URL 拼接）