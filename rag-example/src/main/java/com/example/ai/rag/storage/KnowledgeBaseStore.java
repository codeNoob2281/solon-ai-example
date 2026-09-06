package com.example.ai.rag.storage;

import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.mapper.KbChunkMapper;
import com.example.ai.rag.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * MySQL 知识库存储（文档表 kb_document + 父块表 kb_chunk）
 * <p>
 * 职责（父子分段模式）：
 * <ul>
 *   <li>入库：文档行 + 父块行一次性落库（{@code @Transactional}），
 *       Milvus 子块写入成功后置 INGESTED、失败置 FAILED（尽力而为）；</li>
 *   <li>检索：按父块 id 批量取父块行（子块命中 Milvus 后映射父块内容）；</li>
 *   <li>懒建表：首次使用时执行 {@code CREATE TABLE IF NOT EXISTS}，
 *       对齐 Milvus「首次使用自动建集合」的懒初始化风格——应用启动不要求 MySQL 在线，
 *       首次使用知识库时才建连/建表。</li>
 * </ul>
 * <p>
 * DDL 与 {@code resources/sql/knowledge_base.sql} 保持一致（该文件用于手工初始化/查阅）。
 */
@Slf4j
@Service
public class KnowledgeBaseStore {

    /** 入库状态：写库中（Milvus 未完成） */
    public static final String STATUS_INGESTING = "INGESTING";
    /** 入库状态：完成（MySQL + Milvus 均成功） */
    public static final String STATUS_INGESTED = "INGESTED";
    /** 入库状态：失败（Milvus 写入失败，MySQL 行保留待排查） */
    public static final String STATUS_FAILED = "FAILED";

    private static final String DDL_DOCUMENT = "CREATE TABLE IF NOT EXISTS kb_document (\n"
            + "  id            VARCHAR(32)   NOT NULL COMMENT '文档唯一标识（UUID hex）',\n"
            + "  title         VARCHAR(512)  NULL COMMENT '标题',\n"
            + "  source        VARCHAR(1024) NULL COMMENT '来源（文件绝对路径 / URL，文本入库为空）',\n"
            + "  content_type  VARCHAR(32)   NOT NULL DEFAULT 'text' COMMENT '来源类型 text/file/url',\n"
            + "  content_chars INT           NOT NULL DEFAULT 0 COMMENT '原始内容字符数',\n"
            + "  chunk_count   INT           NOT NULL DEFAULT 0 COMMENT '父块数量',\n"
            + "  status        VARCHAR(16)   NOT NULL DEFAULT 'INGESTING' COMMENT 'INGESTING/INGESTED/FAILED',\n"
            + "  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表'";

    private static final String DDL_CHUNK = "CREATE TABLE IF NOT EXISTS kb_chunk (\n"
            + "  id          VARCHAR(32) NOT NULL COMMENT '区块唯一标识（父块 UUID hex）',\n"
            + "  document_id VARCHAR(32) NOT NULL COMMENT '所属文档 id（kb_document.id）',\n"
            + "  chunk_index INT         NOT NULL DEFAULT 0 COMMENT '父块在文档内的序号',\n"
            + "  content     MEDIUMTEXT  NOT NULL COMMENT '父块内容（供 LLM 上下文）',\n"
            + "  char_count  INT         NOT NULL DEFAULT 0 COMMENT '父块字符数',\n"
            + "  child_count INT         NOT NULL DEFAULT 0 COMMENT '子块数量（存 Milvus）',\n"
            + "  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id),\n"
            + "  KEY idx_kb_chunk_document (document_id)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库父块表'";

    private final JdbcTemplate jdbcTemplate;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    /** 懒建表完成标记（volatile + 双检锁） */
    private volatile boolean initialized = false;

    public KnowledgeBaseStore(JdbcTemplate jdbcTemplate,
                              KbDocumentMapper documentMapper,
                              KbChunkMapper chunkMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 生成实体主键：UUID hex（32 位，与表 VARCHAR(32) / Milvus VarChar(64) 匹配）
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 懒建表：首次调用执行 DDL（幂等），之后直接返回。
     * <p>
     * 失败（MySQL 不在线等）时异常原样上抛，标记不置位，下次调用重试。
     */
    public void ensureTables() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            jdbcTemplate.execute(DDL_DOCUMENT);
            jdbcTemplate.execute(DDL_CHUNK);
            initialized = true;
            log.info("[KB Store] MySQL 知识库表已就绪: kb_document, kb_chunk");
        }
    }

    /**
     * 文档行 + 父块行一次性落库（同一事务）。
     * <p>
     * 文档行以 {@link #STATUS_INGESTING} 落库，Milvus 子块写入完成后再置 INGESTED；
     * MySQL↔Milvus 双写无跨库原子性：Milvus 失败时本事务数据保留（状态推进由调用方负责）。
     *
     * @param document     文档行（id/chunkCount/status 由服务侧填充）
     * @param parentChunks 父块行（id/documentId/childCount 由服务侧填充）
     */
    @Transactional
    public void createDocumentWithChunks(KbDocument document, List<KbChunk> parentChunks) {
        ensureTables();

        documentMapper.insert(document);
        if (parentChunks != null) {
            for (KbChunk chunk : parentChunks) {
                chunkMapper.insert(chunk);
            }
        }
    }

    /**
     * 标记文档入库完成（Milvus 子块写入成功后调用）
     */
    public void markIngested(String documentId) {
        updateStatus(documentId, STATUS_INGESTED);
    }

    /**
     * 标记文档入库失败（Milvus 子块写入失败时尽力而为调用；失败本身不阻断原异常上抛）
     */
    public void markFailed(String documentId) {
        try {
            updateStatus(documentId, STATUS_FAILED);
        } catch (Exception ex) {
            log.warn("[KB Store] markFailed failed: documentId={}, cause={}", documentId, ex.getMessage());
        }
    }

    private void updateStatus(String documentId, String status) {
        ensureTables();

        KbDocument update = new KbDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        // updateById 仅更新非空字段
        documentMapper.updateById(update);
        log.info("[KB Store] document status -> {}: documentId={}", status, documentId);
    }

    /**
     * 按父块 id 批量取父块行（检索侧「子块映射父块」）。
     * 返回顺序不保证与入参一致，由调用方按命中得分排序。
     */
    public List<KbChunk> findParentsByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        ensureTables();
        return chunkMapper.selectBatchIds(ids);
    }
}
