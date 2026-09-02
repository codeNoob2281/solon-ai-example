# Solon AI RAG 知识库示例

## 什么是 RAG

RAG（Retrieval-Augmented Generation，检索增强生成）是一种结合检索技术与生成式人工智能的框架。
它利用外部知识增强生成模型的回答准确性和上下文相关性，适用于需要高准确性、领域知识支持或动态信息的应用场景。

## RAG 的四个技术概念

- Document：文档（或知识）
- DocumentLoader：文档加载器（为文档加载提供接口定义）
- DocumentSplitter：文档分割器（为文档分割提供接口定义）
- Repository：知识库（检索文档）

## 知识库的统一接口

solon-ai 使用"知识库"作为检索的统一接口（Repository）。
知识库分为两类：

- Repository：知识库（只检索）
- RepositoryStorable：可存储知识库（可检索、可存储）

"常规检索"和"矢量相关性检索"两种方案都可以包装成知识库，从而统一接口体验。

## 嵌入模型与重排模型

嵌入模型的作用，是协助知识库将文档内容转为嵌入矢量数据。
重排模型的作用，是知识库搜出结果后，进一步优化排序。

## 应用示例

构建 embeddingModel 后，通过 repository.save() 保存文档；
查询时通过 repository.search() 检索知识，再通过 ChatMessage.ofUserAugment(message, context)
做提示语增强，最后交给 ChatModel 生成回答。
