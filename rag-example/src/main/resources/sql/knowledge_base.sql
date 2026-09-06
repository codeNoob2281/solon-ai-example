-- =====================================================================
-- 知识库 MySQL 表结构（rag-example 父子分段模式）
-- =====================================================================
-- 应用会在首次使用知识库时自动执行等价的 CREATE TABLE IF NOT EXISTS（懒建表，
-- 见 com.example.ai.rag.storage.KnowledgeBaseStore），本文件用于手工初始化与查阅。
--
-- 数据流（父子分段）：
--   文档（kb_document）--父块切分--> 父块（kb_chunk，本库）
--     --子块切分--> 子块（Milvus 集合，独立字段 parent_id = kb_chunk.id）
--   检索：Milvus 检索子块（相似度阈值 + limit）→ 子块映射父块 → 父块内容进 LLM 上下文
-- =====================================================================

CREATE TABLE IF NOT EXISTS kb_document (
  id            VARCHAR(32)   NOT NULL COMMENT '文档唯一标识（UUID hex）',
  title         VARCHAR(512)  NULL COMMENT '标题',
  source        VARCHAR(1024) NULL COMMENT '来源（文件绝对路径 / URL，文本入库为空）',
  content_type  VARCHAR(32)   NOT NULL DEFAULT 'text' COMMENT '来源类型 text/file/url',
  content_chars INT           NOT NULL DEFAULT 0 COMMENT '原始内容字符数',
  chunk_count   INT           NOT NULL DEFAULT 0 COMMENT '父块数量',
  status        VARCHAR(16)   NOT NULL DEFAULT 'INGESTING' COMMENT 'INGESTING/INGESTED/FAILED',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表';

CREATE TABLE IF NOT EXISTS kb_chunk (
  id          VARCHAR(32) NOT NULL COMMENT '区块唯一标识（父块 UUID hex）',
  document_id VARCHAR(32) NOT NULL COMMENT '所属文档 id（kb_document.id）',
  chunk_index INT         NOT NULL DEFAULT 0 COMMENT '父块在文档内的序号',
  content     MEDIUMTEXT  NOT NULL COMMENT '父块内容（供 LLM 上下文）',
  char_count  INT         NOT NULL DEFAULT 0 COMMENT '父块字符数',
  child_count INT         NOT NULL DEFAULT 0 COMMENT '子块数量（存 Milvus）',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_kb_chunk_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库父块表';
