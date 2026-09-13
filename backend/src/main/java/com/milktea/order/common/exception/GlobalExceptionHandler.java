package com.milktea.order.common.exception;

import com.milktea.order.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（覆盖业务 / 校验 / 未知三类，对齐 LLD 3.1 / 3.2）。
 * <p>
 * 统一返回 {@link R}：所有响应 HTTP 状态均为 200，错误以 body 内 code 表达（见 LLD 3.1「0=成功，非 0=业务错误码」）。
 * 注：401/403 已在 {@link ErrorCode} 定义，但其处理归属鉴权任务，本处理器仅覆盖业务/校验/未知三类。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。鉴权类（401/403）按 HLD 5.2 以真实 HTTP 状态码承载；其余业务错误统一 HTTP 200 + body.code。
     */
    @ExceptionHandler(BusinessException.class)
    public <T> R<T> handleBusiness(BusinessException ex, HttpServletResponse response) {
        int code = ex.getCode();
        HttpStatus status = code == 401 ? HttpStatus.UNAUTHORIZED
                : code == 403 ? HttpStatus.FORBIDDEN
                : HttpStatus.OK;
        response.setStatus(status.value());
        log.warn("[T06] BusinessException code={} message={} http={}", code, ex.getMessage(), status.value());
        return R.fail(code, ex.getMessage());
    }

    /** 校验异常（@Valid / 方法参数校验）：code=1001 参数错误。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public <T> R<T> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.PARAM_ERROR.getMessage();
        log.warn("[T04] Validation failed: {}", msg);
        return R.fail(ErrorCode.PARAM_ERROR.getCode(), msg);
    }

    /** 约束校验异常（方法级 @Validated）：code=1001 参数错误。 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public <T> R<T> handleConstraint(ConstraintViolationException ex) {
        log.warn("[T04] ConstraintViolation: {}", ex.getMessage());
        return R.fail(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage());
    }

    /** 未知异常：code=500 服务器内部错误，记录完整堆栈。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public <T> R<T> handleUnknown(Exception ex) {
        log.error("[T04] Unhandled exception", ex);
        return R.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
