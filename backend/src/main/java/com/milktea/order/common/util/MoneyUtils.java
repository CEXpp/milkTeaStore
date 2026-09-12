package com.milktea.order.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具：对外契约要求金额一律为两位小数字符串，避免浮点与科学计数法问题。
 */
public final class MoneyUtils {

    private static final int SCALE = 2;

    private MoneyUtils() {
    }

    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
