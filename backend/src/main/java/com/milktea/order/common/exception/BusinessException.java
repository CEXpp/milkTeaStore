package com.milktea.order.common.exception;

import lombok.Getter;

/**
 * 业务异常：携带 LLD 3.2 错误码与可读信息，由 {@link GlobalExceptionHandler} 统一转成响应体。
 * <p>
 * 用法示例：throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
 * 或 throw new BusinessException(1004, "重复支付");
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage());
    }
}
