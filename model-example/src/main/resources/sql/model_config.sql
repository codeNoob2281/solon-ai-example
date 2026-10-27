-- =====================================================================
-- 模型配置 MySQL 表结构（model-example）
-- =====================================================================
-- 应用会在首次使用模型配置时自动执行等价的 CREATE TABLE IF NOT EXISTS（懒建表，
-- 见 com.example.ai.model.storage.ModelConfigStore），本文件用于手工初始化与查阅。
--
-- 表语义：
--   mc_provider  一行 = 一个 OpenAI 兼容协议的模型服务商（Base URL + API Key）
--   mc_model     一行 = 一个可被聊天页选择的模型（关联 provider，删除供应商时级联删除）
-- =====================================================================

CREATE TABLE IF NOT EXISTS mc_provider (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '供应商 id',
  name       VARCHAR(128) NOT NULL COMMENT '供应商名称（唯一）',
  type       VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '供应商类型',
  base_url   VARCHAR(512) NOT NULL COMMENT '接口地址（OpenAI 兼容 Base URL）',
  api_key    VARCHAR(512) NULL COMMENT 'API Key（明文存储，接口回显脱敏）',
  status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',
  remark     VARCHAR(512) NULL COMMENT '备注',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mc_provider_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型供应商表';

CREATE TABLE IF NOT EXISTS mc_model (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模型 id',
  provider_id       BIGINT        NOT NULL COMMENT '所属供应商 id（mc_provider.id）',
  model_name        VARCHAR(128)  NOT NULL COMMENT '模型名称（调用的 model 字段）',
  display_name      VARCHAR(128)  NULL COMMENT '显示名称',
  context_window    INT           NOT NULL DEFAULT 0 COMMENT '上下文窗口 tokens（0=未知）',
  max_output_tokens INT           NOT NULL DEFAULT 0 COMMENT '最大输出 tokens（0=未知）',
  input_types       VARCHAR(64)   NOT NULL DEFAULT 'text' COMMENT '输入类型：text / text,image',
  reasoning_levels  VARCHAR(2048) NULL COMMENT '预设推理档位 JSON 数组',
  status            TINYINT       NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mc_model_provider (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置表';
