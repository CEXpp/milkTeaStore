import { request } from '@/utils/request'

/**
 * 爆单预测与备料建议（T69，W09）。
 *
 * **只读**：只返回建议，没有任何「执行」通道。系统绝不自动暂停接单、
 * 自动改价或自动上下架——暂停永远由店长在顶栏开关上手动确认（与 T48 同一纪律）。
 */

/** 预测级别：正常 / 偏忙 / 可能爆单 */
export type ForecastLevel = 'NORMAL' | 'BUSY' | 'OVERLOAD'

/** 近期销量结构的一行（备料建议的构成依据） */
export interface ForecastTopItem {
  productName: string
  cupCount: number
}

/** GET /api/admin/forecast 响应 data */
export interface DemandForecast {
  /** 预测是否启用；false 时其余字段无意义（与「算了但正常」可区分） */
  enabled: boolean
  /** 预测窗口（分钟） */
  windowMinutes: number
  /** 预测单量（同星期同时段日均） */
  predictedOrders: number
  level: ForecastLevel
  levelLabel: string
  /** 预测依据的可读说明：让店长能判断这个数可不可信 */
  basis: string
  /** 样本天数；0 表示历史数据不足 */
  sampleDays: number
  /** 备料建议文本；正常时为 null */
  advice: string | null
  /** AI 不可用时为 true（建议退化为模板文本） */
  degraded: boolean
  topProducts: ForecastTopItem[]
}

/**
 * 预测未来一个窗口的单量并给备料建议。
 *
 * 建议里**不会出现库存数量**——系统没有库存模型（任务卡边界「不建库存模型、
 * 不产生出入库记录」），说出「备 20 杯」就是编造。
 */
export function getDemandForecast(): Promise<DemandForecast> {
  return request<DemandForecast>({ url: '/admin/forecast', method: 'get' })
}
