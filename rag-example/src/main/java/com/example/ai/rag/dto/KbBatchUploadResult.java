package com.example.ai.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库批量上传受理结果（多文件上传共用）
 * <p>
 * 语义：接口只做「同步校验（非空/后缀/大小）+ 读取源文本」，通过校验的文件
 * 提交异步入库（切分/向量化在线程池后台执行）后立即返回受理结果；
 * 校验失败的文件计入 {@code failures}（不阻断其余文件）。
 * 入库进度以文档状态呈现（INGESTING → INGESTED / FAILED），管理页列表可见。
 */
@Schema(description = "知识库批量上传受理结果（校验失败的文件计入 failures，通过校验的转后台入库）")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbBatchUploadResult {

    @Schema(description = "提交的文件总数", example = "3")
    private int total;

    @Schema(description = "通过校验、已提交后台入库的文件数", example = "2")
    private int accepted;

    @Schema(description = "校验失败文件数（未入库）", example = "1")
    private int failed;

    @Schema(description = "已受理文件明细（按提交顺序，documentId 供列表定位）")
    private List<AcceptedItem> acceptedItems = new ArrayList<>();

    @Schema(description = "校验失败明细（按提交顺序，含文件名与原因：类型不支持/超过 5MB/文件为空）")
    private List<FailedItem> failures = new ArrayList<>();

    /**
     * 单文件受理明细
     */
    @Schema(description = "单文件受理明细")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcceptedItem {

        @Schema(description = "文件名", example = "产品手册.md")
        private String filename;

        @Schema(description = "文档 id（状态 INGESTING，稍后转 INGESTED/FAILED）")
        private String documentId;
    }

    /**
     * 单文件校验失败明细
     */
    @Schema(description = "单文件校验失败明细")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {

        @Schema(description = "文件名（解析不出时为空串）", example = "bad.exe")
        private String filename;

        @Schema(description = "失败原因", example = "不支持的文件类型（仅支持 .md/.markdown/.txt/.html/.htm）: bad.exe")
        private String message;
    }

    /** 追加一条受理明细并维护计数 */
    public void addAccepted(String filename, String documentId) {
        acceptedItems.add(new AcceptedItem(filename == null ? "" : filename, documentId));
        accepted++;
    }

    /** 追加一条校验失败明细并维护计数 */
    public void addFailure(String filename, String message) {
        failures.add(new FailedItem(filename == null ? "" : filename, message));
        failed++;
    }
}
