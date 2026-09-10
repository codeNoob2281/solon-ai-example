package com.example.ai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ai.rag.entity.KbChunk;
import com.example.ai.rag.entity.KbChunkFullTextRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库父块表 Mapper（MyBatis-Plus BaseMapper + 全文检索自定义 SQL）
 * <p>
 * 自定义 SQL 放在 {@code resources/mapper/KbChunkMapper.xml}，
 * 由 MyBatis-Plus 默认 mapper-locations 加载（classpath 下 mapper 目录、含子目录的 *.xml），
 * 无需额外配置。
 */
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 父块内容 MySQL 全文检索（自然语言模式，ngram 分词；关键词检索策略专用），
     * SQL 见 {@code resources/mapper/KbChunkMapper.xml} 的 {@code selectByFullText}：
     * 只命中已入库（INGESTED）文档的父块，JOIN 带出标题/来源，
     * 分值 ROUND 保留 4 位小数、按分值倒序，minScore 非空时作相关度下限过滤。
     *
     * @param query    关键词查询文本（非空）
     * @param limit    返回条数上限（&gt; 0）
     * @param minScore 相关度下限（可空）
     */
    List<KbChunkFullTextRow> selectByFullText(@Param("query") String query,
                                              @Param("limit") int limit,
                                              @Param("minScore") Double minScore);
}
