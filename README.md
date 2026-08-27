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
└── llm-chat-example/                # 对话示例应用
    ├── pom.xml
    └── src/main/
        ├── java/com/example/ai/example/
        │   ├── AiChatApplication.java             # 入口（启动前加载 .env）
        │   ├── controller/ChatController.java     # POST /chat/sendMessage
        │   ├── service/ChatService.java           # AI 对话服务
        │   └── dto/OpenAiRequest.java             # 请求体
        └── resources/application.yml
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

```bash
curl -X POST http://localhost:8080/chat/sendMessage \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍自己"}'
```

响应：直接返回 AI 回复文本。

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
