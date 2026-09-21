package com.milktea.order.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：{@code {code, message, data}}，code = 0 表示成功。
 */
@Data
public class R<T> implements Serializable {

    private static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "ok";

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(SUCCESS_CODE);
        r.setMessage(SUCCESS_MESSAGE);
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    /**
     * 带数据体的失败响应。
     *
     * <p>供契约要求「错误码 + data 字段」的接口使用（LLD 3.4：AI 不可用时
     * {@code code=1008} 且 {@code data.fallbackText} 给出兜底话术）。</p>
     *
     * @param code    非 0 业务错误码
     * @param message 错误描述
     * @param data    错误响应附带的数据体
     */
    public static <T> R<T> fail(int code, String message, T data) {
        R<T> r = fail(code, message);
        r.setData(data);
        return r;
    }
}
