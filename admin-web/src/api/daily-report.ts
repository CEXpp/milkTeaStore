import { request } from '@/utils/request'

/**
 * 每日经营日报接口（T68，W08）。
 *
 * 日报是**打烊后生成的快照**：数字与异常判定在调用模型之前就由服务端算好，
 * AI 只负责把结论讲成口语。故 `metrics` 永远可复现（与账台统计同源），
 * `narrative` 只是措辞。
 */

/** 渠道结构 */
export interface DailyReportChannel {
  source: string
  orderCount: number
  amount: string
  /** 单量占比（百分比两位小数） */
  sharePercent: string
}

/** TOP 商品 */
export interface DailyReportTopProduct {
  productName: string
  cupCount: number
  amount: string
}

/** 算好的指标（含与近 7 日同期对比） */
export interface DailyReportMetrics {
  revenue: string
  orderCount: number
  cupCount: number
  refundAmount: string
  refundRatioPercent: string
  avgRevenue: string
  avgOrderCount: number
  avgCupCount: number
  /** 营业额较近 7 日同期均值的变化（%）；均值为 0 时为 null */
  revenueChangePercent: number | null
  orderChangePercent: number | null
  /** 对照窗口的样本天数（0 表示没有可比数据） */
  sampleDays: number
  channels: DailyReportChannel[]
  topProducts: DailyReportTopProduct[]
}

/** 异常项（由确定性阈值判定，不由模型发现） */
export interface DailyReportAnomaly {
  code: string
  label: string
  detail: string
}

/** GET /api/admin/daily-report 响应 data；未生成过时为 null */
export interface DailyReport {
  date: string
  metrics: DailyReportMetrics
  anomalies: DailyReportAnomaly[]
  /** 是否明确「今日无异常」 */
  noAnomaly: boolean
  /** AI 表述层文案；降级时为 null */
  narrative: string | null
  /** 是否降级（AI 不可用，仅纯数据版） */
  degraded: boolean
  generatedAt: string
}

/** 读某日日报；未生成过返回 null */
export function getDailyReport(date?: string): Promise<DailyReport | null> {
  return request<DailyReport | null>({ url: '/admin/daily-report', method: 'get', params: { date } })
}

/** 手动生成 / 重跑某日日报（与定时任务同链路，幂等：同一日覆盖同一行） */
export function generateDailyReport(date?: string): Promise<DailyReport> {
  return request<DailyReport>({ url: '/admin/daily-report/generate', method: 'post', params: { date } })
}
