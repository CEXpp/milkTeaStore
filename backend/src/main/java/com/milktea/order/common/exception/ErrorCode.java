package com.milktea.order.common.exception;

/**
 * 错误码表（对齐 LLD 3.2）。
 * <p>
 * code = 0 表示成功；其余为业务/系统错误码，统一经 {@link GlobalExceptionHandler} 包装进响应体。
 *
 * <p><b>码位来源</b>：1001..1008 取自 LLD 3.2 错误码表；LLD 3.2 表止于 1008，故 1009..1013 属扩展码位，
 * 依据为《排期与施工步骤》：1009 由 T31 卡指定为「按顾客限流」，1010..1013 为文件上传类（T08）。
 * 新增业务码须同步维护此处与 LLD。</p>
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),
    UNAUTHORIZED(401, "未登录或令牌过期"),
    FORBIDDEN(403, "无权限"),
    PARAM_ERROR(1001, "参数错误"),
    PRODUCT_NOT_FOUND(1002, "商品不存在或已下架"),
    SPEC_INVALID(1003, "规格选择不合法"),
    ORDER_STATUS_CONFLICT(1004, "订单状态冲突"),
    ORDER_NOT_BELONG(1005, "订单不属于当前顾客"),
    SHOP_PAUSED(1006, "店铺暂停接单"),
    DRAFT_EMPTY(1007, "草稿单为空"),
    AI_UNAVAILABLE(1008, "AI 服务不可用"),
    /** 按顾客限流（LLD 9.2「chat 接口按顾客维度限流 10 次/分钟」）；排期 T31 卡指定码位 1009 */
    RATE_LIMITED(1009, "操作过于频繁，请稍后再试"),
    FILE_TYPE_NOT_ALLOWED(1010, "仅支持图片文件"),
    FILE_TOO_LARGE(1011, "图片大小超出限制"),
    FILE_UPLOAD_FAILED(1012, "文件上传失败"),
    FILE_NOT_FOUND(1013, "文件不存在"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
