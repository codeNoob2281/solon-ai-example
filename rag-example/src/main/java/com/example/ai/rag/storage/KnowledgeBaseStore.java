package com.example.ai.rag.storage;

import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbChunkFullTextRow;
import com.example.ai.rag.entity.KbDocument;
import com.example.ai.rag.mapper.KbChunkMapper;
import com.example.ai.rag.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
 *   <li>检索：按父块 id 批量取父块行（子块命中 Milvus 后映射父块内容）；
 *       以及父块内容全文检索（MySQL FULLTEXT，关键词检索策略 {@code mysql-fulltext}）；</li>
 *   <li>懒建表：首次使用时执行 {@code CREATE TABLE IF NOT EXISTS}，
 *       对齐 Milvus「首次使用自动建集合」的懒初始化风格——应用启动不要求 MySQL 在线，
 *       首次使用知识库时才建连/建表；FULLTEXT 索引（ngram）由关键词检索策略
 *       首次使用时懒建。</li>
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
            + "  doc_type      VARCHAR(16)   NULL COMMENT '文档格式：markdown/html/text',\n"
            + "  content       MEDIUMTEXT    NULL COMMENT '原始全文（重建索引依据；粘贴入库也存此列）',\n"
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
            + "  title       VARCHAR(512) NULL COMMENT '子标题路径（所属章节完整标题路径，\" / \" 连接）',\n"
            + "  content     MEDIUMTEXT  NOT NULL COMMENT '父块内容（供 LLM 上下文）',\n"
            + "  char_count  INT         NOT NULL DEFAULT 0 COMMENT '父块字符数',\n"
            + "  child_count INT         NOT NULL DEFAULT 0 COMMENT '子块数量（存 Milvus）',\n"
            + "  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id),\n"
            + "  KEY idx_kb_chunk_document (document_id)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库父块表'";

    /**
     * 全文索引名（kb_chunk.content，ngram 中文分词；关键词检索策略使用）
     */
    public static final String FULLTEXT_INDEX_NAME = "idx_kb_chunk_content_ft";

    /**
     * 全文索引存在性检查（information_schema 按索引名精确判断，幂等）
     */
    private static final String CHECK_FULLTEXT_INDEX =
            "SELECT COUNT(*) FROM information_schema.statistics"
                    + " WHERE table_schema = DATABASE() AND table_name = 'kb_chunk'"
                    + " AND index_name = '" + FULLTEXT_INDEX_NAME + "' AND index_type = 'FULLTEXT'";

    /**
     * 全文索引 DDL（不并入 CREATE TABLE：避免抬高默认 parent-child 策略的 MySQL 版本门槛，
     * 仅关键词检索策略使用，首次调用时检测并创建）。需 MySQL 5.7.6+（InnoDB ngram 解析器）。
     */
    private static final String DDL_FULLTEXT_INDEX =
            "ALTER TABLE kb_chunk ADD FULLTEXT INDEX " + FULLTEXT_INDEX_NAME + " (content) WITH PARSER ngram";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private KbDocumentMapper documentMapper;

    @Resource
    private KbChunkMapper chunkMapper;

    /** 懒建表完成标记（volatile + 双检锁） */
    private volatile boolean initialized = false;

    /** 懒建全文索引完成标记（volatile + 双检锁，仅关键词检索策略触达） */
    private volatile boolean fulltextIndexReady = false;

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

    /**
     * 标记文档进入入库/重建中状态（重建索引流程开始时调用）
     */
    public void markIngesting(String documentId) {
        updateStatus(documentId, STATUS_INGESTING);
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
     * 按文档 id 取文档行（管理侧：重建/删除前校验与读取原始全文）
     */
    public KbDocument findDocumentById(String documentId) {
        ensureTables();
        return documentMapper.selectById(documentId);
    }

    /**
     * 按文档 id 批量取文档行（问答侧：检索命中 → 来源文档信息解析）
     */
    public List<KbDocument> findDocumentsByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        ensureTables();
        return documentMapper.selectBatchIds(ids);
    }

    /**
     * 取文档全部父块 id（重建/删除前用于按 parent_id 批量删除 Milvus 子块）
     */
    public List<String> findChunkIdsByDocumentId(String documentId) {
        ensureTables();
        return chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                        .select(KbChunk::getId)
                        .eq(KbChunk::getDocumentId, documentId))
                .stream()
                .map(KbChunk::getId)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 替换文档父块行（重建索引核心步骤，同一事务）：
     * 删除该文档全部旧父块 → 插入新父块行 → 更新文档 chunk_count。
     * <p>
     * 父块行 id 使用新生成的 UUID（旧 id 已用于删除 Milvus 子块后废弃）。
     */
    @Transactional
    public void replaceChunks(String documentId, List<KbChunk> newChunks, int chunkCount) {
        ensureTables();

        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        if (newChunks != null) {
            for (KbChunk chunk : newChunks) {
                chunkMapper.insert(chunk);
            }
        }

        KbDocument update = new KbDocument();
        update.setId(documentId);
        update.setChunkCount(chunkCount);
        update.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(update);
        log.info("[KB Store] chunks replaced: documentId={}, chunkCount={}", documentId, chunkCount);
    }

    /**
     * 删除文档（同一事务）：先删父块行再删文档行。
     * Milvus 子块的删除由调用方在此之前尽力而为完成（无跨库原子性）。
     */
    @Transactional
    public void deleteByDocumentId(String documentId) {
        ensureTables();

        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        documentMapper.deleteById(documentId);
        log.info("[KB Store] document deleted: documentId={}", documentId);
    }

    /**
     * 汇总统计：文档数
     */
    public long countDocuments() {
        ensureTables();
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_document", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 汇总统计：父块数
     */
    public long countChunks() {
        ensureTables();
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_chunk", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 汇总统计：文档原始全文总字符数（content_chars 之和，空表回 0）
     */
    public long sumContentChars() {
        ensureTables();
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(content_chars), 0) FROM kb_document", Long.class);
        return sum == null ? 0L : sum;
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

    /**
     * 懒建全文索引：kb_chunk.content 上的 FULLTEXT（ngram 解析器，幂等）。
     * <p>
     * 仅关键词检索策略（{@code mysql-fulltext}）首次使用时触达，默认向量检索路径
     * 不依赖该索引（避免抬高其 MySQL 版本门槛：ngram 需 MySQL 5.7.6+）。
     * 索引已存在（如手工初始化过）则直接跳过；创建失败（版本过旧等）时异常上抛，
     * 标记不置位，下次调用重试。
     */
    public void ensureFulltextIndex() {
        if (fulltextIndexReady) {
            return;
        }
        synchronized (this) {
            if (fulltextIndexReady) {
                return;
            }
            ensureTables();
            Integer exists = jdbcTemplate.queryForObject(CHECK_FULLTEXT_INDEX, Integer.class);
            if (exists == null || exists == 0) {
                try {
                    jdbcTemplate.execute(DDL_FULLTEXT_INDEX);
                    log.info("[KB Store] MySQL 全文索引已创建: kb_chunk.{} (ngram)", FULLTEXT_INDEX_NAME);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("MySQL 全文索引创建失败（ngram 解析器需 MySQL 5.7.6+；"
                            + "也可手工执行: " + DDL_FULLTEXT_INDEX + "）", e);
                }
            }
            fulltextIndexReady = true;
            log.info("[KB Store] MySQL 全文索引已就绪: kb_chunk.{}", FULLTEXT_INDEX_NAME);
        }
    }

    /**
     * 父块内容全文检索（关键词检索策略 {@code mysql-fulltext}）：
     * MATCH...AGAINST 自然语言模式 + ngram 分词，只命中已入库（INGESTED）文档的父块，
     * 按相关度倒序，分值 SQL 内 ROUND 保留 4 位小数。
     *
     * @param query    关键词查询文本（非空）
     * @param limit    返回条数上限（调用方保证 &gt; 0）
     * @param minScore 相关度下限（可选；null 或非正数不过滤。相关度非 0~1 相似度）
     */
    public List<KbChunkFullTextRow> findChunksByFullText(String query, int limit, Double minScore) {
        ensureFulltextIndex();
        return chunkMapper.selectByFullText(query, limit, minScore);
    }
}
