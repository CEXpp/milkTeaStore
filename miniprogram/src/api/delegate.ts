import { request } from '@/utils/request'

/**
 * 取餐凭证转赠接口（T65，C4e · W15）。
 *
 * 原主签发限时一次性委托令牌 → 代取人凭令牌打开凭证页 → 原主可随时撤销。
 * 代取人只看得到取餐码与商品概要，**看不到金额、支付信息与历史订单**。
 */

/** 代取凭证页中的一杯（无单价——价差属金额信息，不向代取人暴露） */
export type DelegatePickupItem = {
  productName: string
  quantity: number
  specSummary: string | null
}

/** GET /api/customer/orders/delegate/{token} 响应 data */
export type DelegatePickup = {
  orderNo: string
  pickupCode: string
  status: string
  items: DelegatePickupItem[]
  expiresAt: string
  /** 本次访问后令牌即失效（打开即核销，恒为 true） */
  usedUp: boolean
  revokedByOwner: boolean
}

/** POST /api/customer/orders/{id}/delegate 请求体 */
export type DelegateIssueRequest = {
  /** 有效期（分钟），缺省 120、上限 720 */
  minutes?: number
  /** 代取人标识（选填，如「李工」），仅供店员识别来者 */
  proxyTag?: string
}

/**
 * 原主签发委托令牌（需登录）。
 *
 * 返回的令牌**只在此刻给一次**，系统内不留可再次查询的明文副本——
 * 页面必须提示用户自行保存（截图或复制发给代取人）。
 */
export function issueDelegate(orderId: number, data: DelegateIssueRequest = {}): Promise<string> {
  return request<string>({ url: `/api/customer/orders/${orderId}/delegate`, method: 'POST', data, auth: true })
}

/** 原主收回委托：撤销后令牌立即失效（需登录） */
export function revokeDelegate(orderId: number): Promise<void> {
  return request<void>({ url: `/api/customer/orders/${orderId}/delegate/revoke`, method: 'POST', auth: true })
}

/**
 * 代取人凭令牌取凭证（**打开即核销，一次性**）。
 *
 * auth: false —— 代取人可能从未登录过小程序（同事顺路代取），
 * 让他先登录再取一杯奶茶是本末倒置；授权由令牌自身承担。
 */
export function redeemDelegate(token: string, proxyTag?: string): Promise<DelegatePickup> {
  return request<DelegatePickup>({
    url: `/api/customer/orders/delegate/${token}`,
    method: 'GET',
    data: proxyTag ? { proxyTag } : undefined,
    auth: false
  })
}
