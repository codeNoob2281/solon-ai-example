package com.example.ai.rag.repository;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Milvus 子块向量仓库（父子分段模式）
 * <p>
 * 为什么不用 solon 内置的 {@code MilvusRepository}：其 Collection 结构固定为
 * {@code id / embedding / content / metadata(JSON)}（Builder 仅支持 collectionName，
 * save 内部按固定字段序列化），无法扩展独立标量字段。本仓库直接基于
 * {@code MilvusClientV2} + {@code EmbeddingModel} 自建集合结构：
 * <pre>
 * id         VarChar(64)  主键（子块唯一标识，缺省自动生成 UUID）
 * parent_id  VarChar(64)  父块唯一标识（= MySQL 分块表 kb_chunk.id），独立标量字段 + TRIE 标量索引
 * embedding  FloatVector  子块向量（IVF_FLAT + COSINE + nlist=128）
 * content    VarChar(65535)  子块文本
 * metadata   JSON         document_id / child_index / source / filename / title 等
 * </pre>
 * <p>
 * 内存模型沿用 solon 的 {@link Document}：子块在 Java 侧以 {@code metadata("parent_id", …)} 携带
 * 父块 id，本仓库在写入时映射为独立字段、检索时映射回 metadata，下游服务代码无感知。
 * <p>
 * 实现接口 {@link RepositoryStorable}（save/search/deleteById/existsById）与
 * {@link RepositoryLifecycle}（init/drop），批次嵌入与进度回调语义与 solon 内置仓库保持一致：
 * 按嵌入 batchSize 分批 → 批量 embed → Upsert，每完成一批回调 {@code (batch, total)}。
 * <p>
 * 检索语义：query 向量化 → Milvus 向量相似度检索 topK → {@link SimilarityUtil#refilter}
 * 按相似度阈值二次过滤（与 QueryCondition 的阈值语义一致）。
 */
public class MilvusChildRepository implements RepositoryStorable, RepositoryLifecycle {

    public static final String FIELD_ID = "id";
    public static final String FIELD_PARENT_ID = "parent_id";
    public static final String FIELD_EMBEDDING = "embedding";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_METADATA = "metadata";

    /**
     * 按 parent_id 批量删除时的分片大小（防过滤表达式过长）
     */
    private static final int DELETE_BATCH_SIZE = 200;

    private final Gson gson = new Gson();
    private final EmbeddingModel embeddingModel;
    private final MilvusClientV2 client;
    private final String collectionName;

    /**
     * @param embeddingModel 嵌入模型（提供 batchSize 分批与向量维度，可能带重试装饰）
     * @param client         Milvus 客户端
     * @param collectionName 子块集合名（不能用 '-'，Milvus 命名限制）
     */
    public MilvusChildRepository(EmbeddingModel embeddingModel, MilvusClientV2 client, String collectionName) {
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.collectionName = collectionName;

        initRepository();
    }

    /**
     * 初始化仓库：集合不存在则创建（含 parent_id 独立字段与标量索引）；
     * 已存在则校验结构必须包含 parent_id 独立字段（拦截旧版结构集合）。
     */
    @Override
    public void initRepository() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());

        if (exists) {
            DescribeCollectionResp describe = client.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (describe.getFieldNames() == null || !describe.getFieldNames().contains(FIELD_PARENT_ID)) {
                throw new IllegalStateException(String.format(
                        "Milvus 集合 '%s' 为旧版结构（缺少独立字段 %s），与父子分段模式不兼容；"
                                + "请删除该集合后重试，或更换 ai.rag.repository.collection-name（MILVUS_COLLECTION）指向新集合",
                        collectionName, FIELD_PARENT_ID));
            }
            return;
        }

        try {
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_PARENT_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_EMBEDDING)
                    .dataType(DataType.FloatVector)
                    .dimension(embeddingModel.dimensions())
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_CONTENT)
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_METADATA)
                    .dataType(DataType.JSON)
                    .build());

            List<IndexParam> indexParams = new ArrayList<>();
            // 主键索引（与 solon 内置仓库一致，缺省索引类型）
            indexParams.add(IndexParam.builder().fieldName(FIELD_ID).build());
            // parent_id 标量索引：TRIE 适用于 VarChar，加速按父块过滤/统计
            indexParams.add(IndexParam.builder()
                    .fieldName(FIELD_PARENT_ID)
                    .indexName("parent_id_index")
                    .indexType(IndexParam.IndexType.TRIE)
                    .build());
            // 向量索引：与 solon 内置仓库一致（IVF_FLAT + COSINE + nlist=128）
            indexParams.add(IndexParam.builder()
                    .fieldName(FIELD_EMBEDDING)
                    .indexName("embedding_index")
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Collections.singletonMap("nlist", 128))
                    .build());

            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();
            client.createCollection(createReq);

            client.getLoadState(GetLoadStateReq.builder()
                    .collectionName(collectionName)
                    .build());
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }

    /**
     * 注销仓库
     */
    @Override
    public void dropRepository() {
        client.dropCollection(DropCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    /**
     * 批量存储子块（支持更新）。
     * <p>
     * 要求每个子块携带 {@code metadata("parent_id", …)}（父子关联的唯一载体，
     * 写入时映射为集合的独立字段）；缺省时抛出 IllegalStateException（编程错误防护）。
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
            Object parentId = doc.getMetadata(FIELD_PARENT_ID);
            if (parentId == null || parentId.toString().trim().isEmpty()) {
                throw new IllegalStateException(String.format(
                        "子块缺少 %s 元数据，无法写入集合 '%s'（docId=%s）", FIELD_PARENT_ID, collectionName, doc.getId()));
            }
        }

        // 按嵌入 batchSize 分批：批量 embedding → Upsert → 进度回调
        List<List<Document>> batchList = ListUtil.partition(documents, embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            embeddingModel.embed(batch);
            batchUpsertDo(batch);

            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }
    }

    private void batchUpsertDo(List<Document> batch) {
        List<JsonObject> rows = batch.stream()
                .map(this::toRow)
                .collect(Collectors.toList());

        client.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());
    }

    /**
     * 子块 Document → Milvus 行：metadata 中的 parent_id 提升为独立字段
     */
    private JsonObject toRow(Document doc) {
        JsonObject row = new JsonObject();
        row.addProperty(FIELD_ID, doc.getId());
        row.addProperty(FIELD_PARENT_ID, String.valueOf(doc.getMetadata(FIELD_PARENT_ID)));
        row.addProperty(FIELD_CONTENT, doc.getContent());
        row.add(FIELD_METADATA, doc.getMetadata() == null ? new JsonObject() : gson.toJsonTree(doc.getMetadata()));
        row.add(FIELD_EMBEDDING, gson.toJsonTree(doc.getEmbedding()));
        return row;
    }

    @Override
    public void deleteById(String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }
        client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .ids(Arrays.asList(ids))
                .build());
    }

    /**
     * 按父块 id 批量删除其全部子块（重建索引 / 删除文档用）。
     * <p>
     * 走 {@code parent_id in ["id1","id2",…]} 标量过滤（parent_id 有 TRIE 索引），
     * 按每批 ≤ {@value #DELETE_BATCH_SIZE} 个 id 分片多次 delete，防过滤表达式过长；
     * 空集合直接返回。
     *
     * @param parentIds 父块 id 集合（kb_chunk.id）
     */
    public void deleteByParentIds(Collection<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return;
        }
        List<String> ids = parentIds.stream()
                .filter(id -> id != null && !id.trim().isEmpty())
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }

        for (List<String> batch : ListUtil.partition(ids, DELETE_BATCH_SIZE)) {
            String filter = "parent_id in [" + batch.stream()
                    .map(MilvusChildRepository::quoteFilterValue)
                    .collect(Collectors.joining(",")) + "]";
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .build());
        }
    }

    /**
     * 读取集合的向量维度（FloatVector 字段的 dimension）。
     * <p>
     * 集合不存在返回 {@link OptionalInt#empty()}；调用方（汇总接口）以
     * {@code EmbeddingModel.dimensions()} 回落——该方法每次调用都发起真实嵌入请求，
     * 因此优先从集合 schema 读取（零嵌入成本）。
     */
    public OptionalInt getVectorDimension() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (!exists) {
            return OptionalInt.empty();
        }

        DescribeCollectionResp describe = client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        CreateCollectionReq.CollectionSchema schema = describe.getCollectionSchema();
        if (schema == null || schema.getFieldSchemaList() == null) {
            return OptionalInt.empty();
        }
        for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
            if (field.getDataType() == DataType.FloatVector && field.getDimension() != null) {
                return OptionalInt.of(field.getDimension());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 过滤表达式字符串值转义（id 为内部生成的 UUID hex，正常不含特殊字符，防御性转义）
     */
    private static String quoteFilterValue(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    @Override
    public boolean existsById(String id) throws IOException {
        return client.get(GetReq.builder()
                .collectionName(collectionName)
                .ids(Arrays.asList(id))
                .build()).getGetResults().size() > 0;
    }

    /**
     * 子块向量检索：query 向量化 → Milvus topK 相似度检索 → 按阈值二次过滤。
     * <p>
     * 返回的子块 Document 的 metadata 中会包含 parent_id（来自集合独立字段），
     * 供上层「子块映射父块」使用。
     */
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        FloatVec queryVector = new FloatVec(embeddingModel.embed(condition.getQuery()));

        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(queryVector))
                .topK(condition.getLimit())
                .outputFields(Arrays.asList(FIELD_PARENT_ID, FIELD_CONTENT, FIELD_METADATA))
                .build();

        SearchResp searchResp = client.search(searchReq);

        Stream<Document> docs = searchResp.getSearchResults().stream()
                .flatMap(r -> r.stream())
                .map(this::toDocument);

        // 按相似度阈值二次过滤（与 solon 内置仓库语义一致）
        return SimilarityUtil.refilter(docs, condition);
    }

    /**
     * 搜索结果 → 子块 Document：独立字段 parent_id 映射回 metadata
     */
    private Document toDocument(SearchResp.SearchResult result) {
        Map<String, Object> entity = result.getEntity();
        String content = (String) entity.get(FIELD_CONTENT);
        JsonObject metadataJson = (JsonObject) entity.get(FIELD_METADATA);
        Map<String, Object> metadata = metadataJson != null ? gson.fromJson(metadataJson, Map.class) : null;

        Document doc = new Document((String) result.getId(), content, metadata, result.getScore());

        Object parentId = entity.get(FIELD_PARENT_ID);
        if (parentId != null) {
            doc.metadata(FIELD_PARENT_ID, parentId.toString());
        }
        return doc;
    }
}
