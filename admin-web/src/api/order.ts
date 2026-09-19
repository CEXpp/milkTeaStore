import { request } from '@/utils/request'

/** 订单状态（LLD 4.1 状态机） */
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'PREPARING' | 'COMPLETED' | 'CLOSED' | 'VOIDED'

/** 订单来源渠道（LLD 3.5 看板 / 3.5.4 统计） */
export type OrderSource = 'MINI_PROGRAM' | 'AI' | 'COUNTER'

/** 下单条目（柜台单与顾客下单同构，LLD 3.3 / 3.5） */
export interface OrderItemPayload {
  productId: number
  /** 已选规格选项 id 列表：单选组恰选 1 项，多选组 0..n 项（LLD 4.5） */
  optionIds: number[]
  quantity: number
}

/** 看板「待制作」卡片（GET /api/admin/orders/board → pending[]，按支付时间正序） */
export interface BoardPendingOrder {
  orderId: number
  pickupCode: string
  source: OrderSource
  items: string[]
  totalAmount: string
  paidAt: string
  minutesWaiting: number
}

/** 看板「制作中」卡片（GET /api/admin/orders/board → preparing[]，按开始时间正序） */
export interface BoardPreparingOrder {
  orderId: number
  pickupCode: string
  source: OrderSource
  items: string[]
  totalAmount: string
  startedAt: string
  minutesPreparing: number
}

/** 看板今日摘要（T14 起为四数：营业额/订单数/杯数/退款额，口径 SRS 6.5） */
export interface BoardTodaySummary {
  orderCount: number
  amount: string
  cupCount: number
  /** 当日作废单实付合计（单独列示，不并入营业额） */
  refundAmount: string
}

/** GET /api/admin/orders/board 响应 data */
export interface OrderBoardResult {
  pending: BoardPendingOrder[]
  preparing: BoardPreparingOrder[]
  today: BoardTodaySummary
}

/** start / complete / void 响应 data：更新后的订单摘要（字段以看板卡片结构为基准，联调期如有出入以后端为准） */
export interface AdminOrderSummary {
  orderId: number
  orderNo: string
  status: OrderStatus
  pickupCode: string | null
  source: OrderSource
  items: string[]
  totalAmount: string
  paidAt: string | null
  startedAt: string | null
  completedAt: string | null
  voidedAt: string | null
  voidReason: string | null
}

/** POST /api/admin/counter-orders 请求体 */
export interface CounterOrderRequest {
  items: OrderItemPayload[]
  remark?: string
}

/** POST /api/admin/counter-orders 响应 data */
export interface CounterOrderResult {
  orderId: number
  orderNo: string
  pickupCode: string
  totalAmount: string
}

/** POST /api/admin/orders/{id}/void 请求体 */
export interface VoidOrderRequest {
  reason: string
}

/** 看板全量轮询（3 秒间隔，LLD 3.5 / 4.4）：pending + preparing + 今日摘要 */
export function getOrderBoard(): Promise<OrderBoardResult> {
  return request<OrderBoardResult>({ url: '/admin/orders/board', method: 'get' })
}

/** PAID → PREPARING（开始制作）；非法迁移后端返回 1004 */
export function startOrder(id: number): Promise<AdminOrderSummary> {
  return request<AdminOrderSummary>({ url: `/admin/orders/${id}/start`, method: 'post' })
}

/** PREPARING → COMPLETED（出餐完成） */
export function completeOrder(id: number): Promise<AdminOrderSummary> {
  return request<AdminOrderSummary>({ url: `/admin/orders/${id}/complete`, method: 'post' })
}

/** 作废（仅限 PAID，未开始制作）；reason 为必填作废原因，退款额进统计 */
export function voidOrder(id: number, data: VoidOrderRequest): Promise<AdminOrderSummary> {
  return request<AdminOrderSummary>({ url: `/admin/orders/${id}/void`, method: 'post', data })
}

/** 柜台人工点单：创建即直接 PAID（当面收款），分配取餐码，customer_id 为 NULL */
export function createCounterOrder(data: CounterOrderRequest): Promise<CounterOrderResult> {
  return request<CounterOrderResult>({ url: '/admin/counter-orders', method: 'post', data })
}
