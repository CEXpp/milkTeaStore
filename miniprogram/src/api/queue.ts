import { request } from '@/utils/request'

/**
 * 顾客端队列预估与稍后提醒接口（T46 内核 / T47 下单前预期管理 / T49 等待预估）。
 *
 * 预估数据全部来自后端 T46 队列预估内核的真实聚合（LLD 11.6），前端不做任何本地估算。
 */

/** 队列预估（T46 契约；带 orderId 时 position 有值，不带时为 null） */
export interface QueueEstimate {
  /** 订单主键；null = 下单前预估（未指定订单） */
  orderId: number | null
  /** 队列序号（1 起）；null = 下单前预估；0 = 订单不在队列中 */
  position: number | null
  /** 前序队列工作量（杯） */
  queueCups: number
  /** 预估等待（分钟） */
  etaMinutes: number
  /** 误差区间下限（分钟，非负） */
  etaLow: number
  /** 误差区间上限（分钟） */
  etaHigh: number
  /** 计算时间戳 */
  computedAt: string
}

/** 稍后提醒登记结果（T47） */
export interface RemindResult {
  taskId: number
  /** 计划提醒时间（yyyy-MM-dd HH:mm:ss） */
  remindAt: string
  /** 实际生效的延迟分钟数 */
  delayMinutes: number
}

/**
 * 队列预估。
 *
 * @param orderId 传则取本单预估（T49 进行中订单页）；不传取下单前预估（T47 结算页）
 */
export function getQueueEstimate(orderId?: number): Promise<QueueEstimate> {
  const query = orderId ? `?orderId=${orderId}` : ''
  return request<QueueEstimate>({ url: `/api/customer/queue/estimate${query}`, auth: true })
}

/**
 * 登记「稍后提醒我再点」（T47）。
 *
 * 只登记一条提醒任务，**不产生任何订单**（不占取餐码、不进统计、不影响超时关单规则）。
 */
export function scheduleRemind(delayMinutes?: number): Promise<RemindResult> {
  return request<RemindResult>({
    url: '/api/customer/remind',
    method: 'POST',
    data: { delayMinutes },
    auth: true
  })
}

/** 查询当前待发提醒；无待发提醒时返回 null。 */
export function getPendingRemind(): Promise<RemindResult | null> {
  return request<RemindResult | null>({ url: '/api/customer/remind', auth: true })
}
