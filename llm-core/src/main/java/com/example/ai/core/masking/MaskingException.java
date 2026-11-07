package com.example.ai.core.masking;

/**
 * 脱敏/还原异常
 * <p>
 * 还原方向遵循 fail-closed：映射表中查不到对应掩码时抛出本异常，拦截本次工具调用，
 * 避免把掩码字面量写入文件造成数据损坏。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class MaskingException extends RuntimeException {

    public MaskingException(String message) {
        super(message);
    }
}
