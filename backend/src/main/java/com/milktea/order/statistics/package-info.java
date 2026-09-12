/**
 * 职责域：statistics —— 账台统计。
 *
 * <p>最关键的设计决策：口径常量化，单点维护。
 * 所有统计口径（指标定义、聚合规则）收敛为常量与配置，
 * 统一在单一位置维护，杜绝多处分叉导致的口径不一致。</p>
 */
package com.milktea.order.statistics;
