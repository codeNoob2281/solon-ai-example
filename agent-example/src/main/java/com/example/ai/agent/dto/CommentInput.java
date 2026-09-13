package com.example.ai.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户评论情感分析输入
 * <pre>{@code
 * { "commentId": "c20260912001", "content": "手机外观很漂亮，电池不太耐用，这个价格还算划算。" }
 * }</pre>
 */
@Data
@Schema(description = "用户评论情感分析输入")
public class CommentInput {

    @Schema(description = "评论 id", example = "c20260912001")
    private String commentId;

    @Schema(description = "评论内容", example = "手机外观很漂亮，电池不太耐用，这个价格还算划算。")
    private String content;
}
