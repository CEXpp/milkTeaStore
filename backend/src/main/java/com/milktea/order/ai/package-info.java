/**
 * 职责域：ai —— 智能体编排。
 *
 * <p>最关键的设计决策：复用既有链路，支付无工具。
 * 智能体直接编排已有的 user/product/order/payment 业务链路，
 * 不在工具集中暴露任何支付类工具，避免越权发起资金操作。</p>
 */
package com.milktea.order.ai;
