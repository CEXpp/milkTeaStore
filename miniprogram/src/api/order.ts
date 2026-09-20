import { request, type PageResult } from '@/utils/request'

/**
 * 顾客端订单接口（LLD 3.3 / 3.4）。
 * 下单与支付类接口需顾客 JWT（auth: true，request 层会自动补登录）。
 */

/** 订单项请求（只描述「要什么」，不含任何价格字段——价格一律后端计算） */
export type OrderItemRequest = {
  productId: number
  optionIds: number[]
  quantity: number
}

/** POST /api/customer/orders 请求体 */
export type OrderCreateRequest = {
  items: OrderItemRequest[]
  remark?: string
}

/** 下单响应中的订单项快照 */
export interface OrderCreatedItem {
  productName: string
  quantity: number
  unitPrice: string
  optionNames: string[]
  itemAmount: string
}

/** POST /api/customer/orders 响应 data */
export interface OrderCreateResult {
  id: number
  orderNo: string
  status: string
  /** 应付总额（两位小数字符串，后端计价引擎计算） */
  totalAmount: string
  /** 支付截止时间（创建时间 + 15 分钟） */
  expireAt: string
  items: OrderCreatedItem[]
}

/** POST /api/customer/orders/{id}/pay 响应 data */
export interface OrderPayResult {
  id: number
  status: string
  pickupCode: string
  paidAt: string
  payChannel: string
}

/** 轮询专用轻量状态（3 秒间隔） */
export interface OrderStatusResult {
  status: string
  pickupCode: string | null
  /** 该单之前处于 PAID/PREPARING 的单数（前端显示「排队第 seq+1 位」） */
  seq: number
}

/** 规格快照 */
export interface OptionSnapshot {
  groupName: string
  optionName: string
  priceDelta: string
}

/** 订单详情中的订单项 */
export interface OrderDetailItem {
  productId: number
  productName: string
  quantity: number
  basePrice: string
  unitPrice: string
  options: OptionSnapshot[]
  itemAmount: string
}

/** GET /api/customer/orders/{id} 响应 data */
export interface OrderDetail {
  id: number
  orderNo: string
  /** MINI_PROGRAM / AI / COUNTER */
  source: string
  status: string
  pickupCode: string | null
  totalAmount: string
  remark: string | null
  createdAt: string
  paidAt: string | null
  startedAt: string | null
  completedAt: string | null
  closedAt: string | null
  voidedAt: string | null
  voidReason: string | null
  items: OrderDetailItem[]
}

/** 订单列表项（active 与历史分页共用） */
export interface OrderListItem {
  id: number
  status: string
  pickupCode: string | null
  totalAmount: string
  createdAt: string
  /** 商品摘要，如 "珍珠奶茶x1(大杯/少冰/珍珠)" */
  items: string[]
  paidAt: string | null
  completedAt: string | null
  closedAt: string | null
  voidedAt: string | null
}

/** 创建订单（PENDING_PAYMENT），实付金额以返回的 totalAmount 为准 */
export function createOrder(data: OrderCreateRequest): Promise<OrderCreateResult> {
  return request<OrderCreateResult>({ url: '/api/customer/orders', method: 'POST', data, auth: true })
}

/** 发起支付（练手期为 Mock 通道，立即成功并分配取餐码） */
export function payOrder(orderId: number): Promise<OrderPayResult> {
  return request<OrderPayResult>({ url: `/api/customer/orders/${orderId}/pay`, method: 'POST', auth: true })
}

/** 进行中订单列表（PENDING_PAYMENT / PAID / PREPARING） */
export function getActiveOrders(): Promise<OrderListItem[]> {
  return request<OrderListItem[]>({ url: '/api/customer/orders/active', auth: true })
}

/** 历史订单分页（全部状态，创建时间倒序） */
export function getOrderHistory(page: number, size: number): Promise<PageResult<OrderListItem>> {
  return request<PageResult<OrderListItem>>({
    url: `/api/customer/orders?page=${page}&size=${size}`,
    auth: true
  })
}

/** 订单详情（含规格快照明细） */
export function getOrderDetail(orderId: number): Promise<OrderDetail> {
  return request<OrderDetail>({ url: `/api/customer/orders/${orderId}`, auth: true })
}

/** 轮询订单轻量状态 */
export function getOrderStatus(orderId: number): Promise<OrderStatusResult> {
  return request<OrderStatusResult>({ url: `/api/customer/orders/${orderId}/status`, auth: true })
}
