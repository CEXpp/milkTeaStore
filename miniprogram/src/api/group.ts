import { request } from '@/utils/request'
import type { OrderItemRequest, OrderPayResult } from './order'

/**
 * 顾客端拼单接口（T63 · T64，W13）。
 *
 * 生命周期：创建池 → 各人提交自己的选品 → 发起人冻结 → 发起人支付（生成一张订单）。
 * 金额一律后端现算，前端提交的任何金额都不被采信。
 */

/** 拼单阶段（与后端 GroupCart.STATUS_* 一致） */
export type GroupStatus = 'OPEN' | 'FROZEN' | 'CONVERTING' | 'CONVERTED' | 'EXPIRED'

/**
 * POST /api/customer/groups 请求体。
 *
 * 用 `type` 而非 `interface`：request 层的 data 参数类型是 `Record<string, unknown>`，
 * TS 只给 type 别名的对象字面量类型隐式索引签名，interface 没有——用 interface 会编译不过。
 */
export type GroupCreateRequest = {
  /** 收单时长（分钟），缺省 30、上限 120 */
  minutes?: number
}

/** 池中的一杯（含实时计价） */
export interface GroupItem {
  productId: number
  productName: string
  quantity: number
  /** 规格一行摘要（大杯 · 少冰 · 五分糖 · 珍珠） */
  specText: string | null
  unitPrice: string
  itemAmount: string
}

/** 成员选品 */
export interface GroupMember {
  customerId: number
  /** 成员标识（如「003 王工」），可能为 null */
  tag: string | null
  items: GroupItem[]
}

/** GET /api/customer/groups/{uuid} 响应 data */
export interface GroupCart {
  groupUuid: string
  ownerId: number
  status: GroupStatus
  /** 是否已到期（到点仍未支付即不可转正式单） */
  expired: boolean
  /** 当前登录顾客是否已在池中（前端据此显示「退出拼单」还是「提交选品」） */
  joined: boolean
  /** 当前身份能否冻结：仅发起人且处于 OPEN 且未到期 */
  canFreeze: boolean
  /** 当前身份能否支付 / 放弃：仅发起人且已冻结（后端判定，避免非发起人点了才报错） */
  canPay: boolean
  expiresAt: string
  /** 合计金额（后端现算） */
  totalAmount: string
  cupCount: number
  /** 失效提示（如「珍珠奶茶已下架」）；无则 null */
  invalidTip: string | null
  members: GroupMember[]
}

/** POST /api/customer/groups/{uuid}/items 请求体（type 而非 interface，理由同上） */
export type GroupItemSubmitRequest = {
  /** 成员标识（如「003 王工」），选填 */
  tag?: string
  /** 本人选品；空数组表示退出拼单 */
  items: OrderItemRequest[]
}

/** 创建拼单：返回 groupUuid（分享口令的载体） */
export function createGroup(data: GroupCreateRequest = {}): Promise<string> {
  return request<string>({ url: '/api/customer/groups', method: 'POST', data, auth: true })
}

/** 查看拼单池（含实时计价） */
export function getGroup(uuid: string): Promise<GroupCart> {
  return request<GroupCart>({ url: `/api/customer/groups/${uuid}`, method: 'GET', auth: true })
}

/** 提交 / 更新自己的选品；传空 items 即退出拼单 */
export function submitGroupItems(uuid: string, data: GroupItemSubmitRequest): Promise<void> {
  return request<void>({ url: `/api/customer/groups/${uuid}/items`, method: 'POST', data, auth: true })
}

/** 发起人冻结：冻结后他人不可再加入或改单 */
export function freezeGroup(uuid: string): Promise<void> {
  return request<void>({ url: `/api/customer/groups/${uuid}/freeze`, method: 'POST', auth: true })
}

/** 发起人支付：生成一张订单（一个取餐码 + N 个订单项） */
export function payGroup(uuid: string): Promise<OrderPayResult> {
  return request<OrderPayResult>({ url: `/api/customer/groups/${uuid}/pay`, method: 'POST', auth: true })
}

/** 发起人放弃：池失效，不生成任何订单 */
export function abandonGroup(uuid: string): Promise<void> {
  return request<void>({ url: `/api/customer/groups/${uuid}/abandon`, method: 'POST', auth: true })
}
