import { request } from '@/utils/request'

/**
 * 队列域接口（T48 动态接单节奏；数据来自 T46 队列预估内核）。
 * 契约：GET /api/admin/queue/pace。
 */

/** 接单节奏档位 */
export type QueuePaceLevel = 'NORMAL' | 'BUSY' | 'OVERLOAD'

/** GET /api/admin/queue/pace 响应 data */
export interface QueuePace {
  /** 压力档位：正常 / 偏忙 / 拥挤 */
  level: QueuePaceLevel
  /** 当前队列工作量（杯） */
  totalCups: number
  /** 此刻新下单的预估等待（分钟） */
  etaMinutes: number
  /** 误差区间下限（分钟） */
  etaLow: number
  /** 误差区间上限（分钟） */
  etaHigh: number
  /** 生效的偏忙阈值（杯） */
  busyCups: number
  /** 生效的拥挤阈值（杯） */
  overloadCups: number
  /** 是否建议暂停接单（仅拥挤档为 true；仍是建议，系统不会自动暂停） */
  suggestPause: boolean
  /** 给店长的建议文案 */
  suggestion: string
  /** 计算时间戳 */
  computedAt: string
}

/**
 * 接单节奏与建议。
 *
 * 只读接口：返回的 {@link QueuePace.suggestPause} 只是**建议**——暂停/恢复接单必须由店长
 * 在顶栏开关键上手动确认（任务卡验收项「任何情况下不自动暂停接单」）。
 */
export function getQueuePace(): Promise<QueuePace> {
  return request<QueuePace>({ url: '/admin/queue/pace', method: 'get' })
}
