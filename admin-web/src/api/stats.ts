import { request, type PageQuery, type PageResult } from '@/utils/request'
import type { OrderSource, OrderStatus } from './order'

/** GET /api/admin/stats/summary 查询参数 */
export interface StatsSummaryQuery {
  /** yyyy-MM-dd，缺省为今天 */
  date?: string
}

/** 渠道维度统计 */
export interface StatsChannelItem {
  source: OrderSource
  orderCount: number
  amount: string
}

/** GET /api/admin/stats/summary 响应 data（今日概览） */
export interface StatsSummary {
  date: string
  /** 营业额=当日已支付口径，扣除当日作废退款额 */
  totalAmount: string
  orderCount: number
  /** 主饮品件数 */
  cupCount: number
  /** 当日作废单退款额 */
  refundAmount: string
  channel: StatsChannelItem[]
}

/** GET /api/admin/stats/trend 查询参数 */
export interface StatsTrendQuery {
  /** 近 N 日，如 7 */
  days?: number
}

/** 趋势折线单点（ECharts 数据源） */
export interface StatsTrendItem {
  date: string
  orderCount: number
  amount: string
}

/** GET /api/admin/stats/ranking 查询参数 */
export interface StatsRankingQuery {
  range?: 'today' | '7d'
  /** 取前 N 名，默认 10 */
  top?: number
}

/** 销量排行条目 */
export interface StatsRankingItem {
  productId: number
  productName: string
  cupCount: number
  amount: string
}

/** GET /api/admin/stats/orders 查询参数（按日订单流水明细，分页） */
export interface StatsOrderQuery extends PageQuery {
  /** yyyy-MM-dd，缺省为今天 */
  date?: string
  status?: OrderStatus
}

/** 订单流水明细条目（含状态/取餐码/渠道/金额/时间组/作废原因，供对账） */
export interface StatsOrderItem {
  orderId: number
  orderNo: string
  pickupCode: string | null
  source: OrderSource
  status: OrderStatus
  items: string[]
  totalAmount: string
  createdAt: string
  paidAt: string | null
  completedAt: string | null
  voidedAt: string | null
  voidReason: string | null
}

/** 账台统计：今日概览（GET /api/admin/stats/summary） */
export function getStatsSummary(params: StatsSummaryQuery = {}): Promise<StatsSummary> {
  return request<StatsSummary>({ url: '/admin/stats/summary', method: 'get', params })
}

/** 账台统计：近 N 日趋势（GET /api/admin/stats/trend） */
export function getStatsTrend(params: StatsTrendQuery = {}): Promise<StatsTrendItem[]> {
  return request<StatsTrendItem[]>({ url: '/admin/stats/trend', method: 'get', params })
}

/** 账台统计：销量排行（GET /api/admin/stats/ranking，range=today|7d） */
export function getStatsRanking(params: StatsRankingQuery = {}): Promise<StatsRankingItem[]> {
  return request<StatsRankingItem[]>({ url: '/admin/stats/ranking', method: 'get', params })
}

/** 账台统计：按日订单流水明细（GET /api/admin/stats/orders，分页） */
export function getStatsOrders(params: StatsOrderQuery = {}): Promise<PageResult<StatsOrderItem>> {
  return request<PageResult<StatsOrderItem>>({ url: '/admin/stats/orders', method: 'get', params })
}
