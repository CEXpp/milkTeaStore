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
}
