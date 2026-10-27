package com.example.ai.model.storage;

import com.example.ai.model.entity.McModel;
import com.example.ai.model.entity.McProvider;
import com.example.ai.model.mapper.McModelMapper;
import com.example.ai.model.mapper.McProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 模型配置存储（供应商表 mc_provider + 模型表 mc_model）
 * <p>
 * 职责：
 * <ul>
 *   <li>供应商 CRUD（删除时级联删除其全部模型，同一事务）；</li>
 *   <li>模型 CRUD（按供应商查列表 / 按 id 取单条）；</li>
 *   <li>懒建表：首次使用时执行 {@code CREATE TABLE IF NOT EXISTS}，
 *       沿用 rag-example 知识库的懒初始化风格——应用启动不要求 MySQL 在线。</li>
 * </ul>
 * DDL 与 {@code resources/sql/model_config.sql} 保持一致（该文件用于手工初始化/查阅）。
 */
@Slf4j
@Service
public class ModelConfigStore {

    private static final String DDL_PROVIDER = "CREATE TABLE IF NOT EXISTS mc_provider (\n"
            + "  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '供应商 id',\n"
            + "  name       VARCHAR(128) NOT NULL COMMENT '供应商名称（唯一）',\n"
            + "  type       VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '供应商类型',\n"
            + "  base_url   VARCHAR(512) NOT NULL COMMENT '接口地址（OpenAI 兼容 Base URL）',\n"
            + "  api_key    VARCHAR(512) NULL COMMENT 'API Key（明文存储，接口回显脱敏）',\n"
            + "  status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',\n"
            + "  remark     VARCHAR(512) NULL COMMENT '备注',\n"
            + "  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id),\n"
            + "  UNIQUE KEY uk_mc_provider_name (name)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型供应商表'";

    private static final String DDL_MODEL = "CREATE TABLE IF NOT EXISTS mc_model (\n"
            + "  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模型 id',\n"
            + "  provider_id       BIGINT        NOT NULL COMMENT '所属供应商 id（mc_provider.id）',\n"
            + "  model_name        VARCHAR(128)  NOT NULL COMMENT '模型名称（调用的 model 字段）',\n"
            + "  display_name      VARCHAR(128)  NULL COMMENT '显示名称',\n"
            + "  context_window    INT           NOT NULL DEFAULT 0 COMMENT '上下文窗口 tokens（0=未知）',\n"
            + "  max_output_tokens INT           NOT NULL DEFAULT 0 COMMENT '最大输出 tokens（0=未知）',\n"
            + "  input_types       VARCHAR(64)   NOT NULL DEFAULT 'text' COMMENT '输入类型：text / text,image',\n"
            + "  reasoning_levels  VARCHAR(2048) NULL COMMENT '预设推理档位 JSON 数组',\n"
            + "  status            TINYINT       NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',\n"
            + "  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id),\n"
            + "  KEY idx_mc_model_provider (provider_id)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置表'";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private McProviderMapper providerMapper;

    @Resource
    private McModelMapper modelMapper;

    /** 懒建表完成标记（volatile + 双检锁） */
    private volatile boolean initialized = false;

    /**
     * 懒建表：首次调用执行 DDL（幂等），之后直接返回。
     * <p>失败（MySQL 不在线等）时异常原样上抛，标记不置位，下次调用重试。
     */
    public void ensureTables() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            jdbcTemplate.execute(DDL_PROVIDER);
            jdbcTemplate.execute(DDL_MODEL);
            initialized = true;
            log.info("[Model Config] MySQL 模型配置表已就绪: mc_provider, mc_model");
        }
    }

    // ------------------------------------------------------------
    // 供应商

    /** 供应商列表（按创建时间倒序，不分页——配置页按列表整体展示） */
    public List<McProvider> listProviders() {
        ensureTables();
        return providerMapper.selectList(new LambdaQueryWrapper<McProvider>()
                .orderByDesc(McProvider::getCreatedAt)
                .orderByDesc(McProvider::getId));
    }

    public McProvider findProviderById(Long id) {
        if (id == null) {
            return null;
        }
        ensureTables();
        return providerMapper.selectById(id);
    }

    /** 按名称查供应商（新增/改名时做唯一性校验） */
    public McProvider findProviderByName(String name) {
        ensureTables();
        return providerMapper.selectOne(new LambdaQueryWrapper<McProvider>()
                .eq(McProvider::getName, name)
                .last("LIMIT 1"));
    }

    public void insertProvider(McProvider provider) {
        ensureTables();
        provider.setCreatedAt(LocalDateTime.now());
        provider.setUpdatedAt(provider.getCreatedAt());
        providerMapper.insert(provider);
    }

    public void updateProvider(McProvider provider) {
        ensureTables();
        provider.setUpdatedAt(LocalDateTime.now());
        providerMapper.updateById(provider);
    }

    /**
     * 删除供应商并级联删除其全部模型（同一事务）
     */
    @Transactional
    public void deleteProviderCascade(Long providerId) {
        ensureTables();
        modelMapper.delete(new LambdaQueryWrapper<McModel>().eq(McModel::getProviderId, providerId));
        providerMapper.deleteById(providerId);
        log.info("[Model Config] provider deleted with its models: providerId={}", providerId);
    }

    // ------------------------------------------------------------
    // 模型

    /** 某供应商下的模型列表（按 id 升序，保持配置顺序） */
    public List<McModel> listModelsByProvider(Long providerId) {
        if (providerId == null) {
            return Collections.emptyList();
        }
        ensureTables();
        return modelMapper.selectList(new LambdaQueryWrapper<McModel>()
                .eq(McModel::getProviderId, providerId)
                .orderByAsc(McModel::getId));
    }

    /** 全部启用供应商下的启用模型（聊天页模型选择器数据源） */
    public List<McModel> listEnabledModels() {
        ensureTables();
        return modelMapper.selectList(new LambdaQueryWrapper<McModel>()
                .eq(McModel::getStatus, 1)
                .orderByAsc(McModel::getProviderId)
                .orderByAsc(McModel::getId));
    }

    public McModel findModelById(Long id) {
        if (id == null) {
            return null;
        }
        ensureTables();
        return modelMapper.selectById(id);
    }

    /** 同供应商下是否已存在同名模型（新增/编辑时唯一性校验） */
    public McModel findModelByProviderAndName(Long providerId, String modelName, Long excludeId) {
        ensureTables();
        LambdaQueryWrapper<McModel> wrapper = new LambdaQueryWrapper<McModel>()
                .eq(McModel::getProviderId, providerId)
                .eq(McModel::getModelName, modelName);
        if (excludeId != null) {
            wrapper.ne(McModel::getId, excludeId);
        }
        return modelMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    public void insertModel(McModel model) {
        ensureTables();
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(model.getCreatedAt());
        modelMapper.insert(model);
    }

    public void updateModel(McModel model) {
        ensureTables();
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
    }

    public void deleteModel(Long id) {
        ensureTables();
        modelMapper.deleteById(id);
    }

    /** 某供应商下的模型数量（供应商列表页展示 + 删除确认文案） */
    public long countModelsByProvider(Long providerId) {
        ensureTables();
        Long count = modelMapper.selectCount(new LambdaQueryWrapper<McModel>()
                .eq(McModel::getProviderId, providerId));
        return count == null ? 0L : count;
    }
}
