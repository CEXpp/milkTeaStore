package com.milktea.order.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 整单计价结果。
 */
@Data
public class PricingResult {

    private List<PricedItem> items;

    /** Σ itemAmount，无优惠体系、无抹零 */
    private BigDecimal totalAmount;
}
