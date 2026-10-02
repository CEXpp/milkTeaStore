import { request } from '@/utils/request'

/**
 * 门店开关域接口（T23）：暂停接单的读（公开 shop-status）与写（商家 PUT pause）。
 * 契约：LLD 3.3 GET /api/customer/shop-status、LLD 3.5.5 PUT /api/admin/shop/pause。
 */

/** PUT /api/admin/shop/pause 请求体。开启暂停后顾客端下单返回 1006；进行中订单不受影响。 */
export interface ShopPauseRequest {
  paused: boolean
  /** 暂停提示语（顾客端展示，如“高峰期制作中，稍后开放点单”） */
  notice?: string
}

/** PUT /api/admin/shop/pause 响应 data */
export interface ShopPauseResult {
  paused: boolean
}

/** GET /api/customer/shop-status 响应 data（公开接口，无需 token） */
export interface ShopStatus {
  paused: boolean
  /**
   * 营业公告（T62）：未发布 / 已撤下时为 null。
   *
   * 后端已归一化——空白一律回 null，前端只判一次 `notice != null` 即可。
   */
  notice: string | null
}

/** PUT /api/admin/shop/notice 请求体（T62）：传空串或 null 即撤下公告 */
export interface ShopNoticeRequest {
  /** 公告正文，最多 60 字 */
  notice?: string | null
}

/** PUT /api/admin/shop/notice 响应 data：归一化后的公告（撤下时为 null） */
export interface ShopNoticeResult {
  notice: string | null
}

/** 门店营业状态（看板顶栏开关的初始值来源） */
export function getShopStatus(): Promise<ShopStatus> {
  return request<ShopStatus>({ url: '/customer/shop-status', method: 'get' })
}

/** 门店开关：暂停 / 恢复接单 */
export function updateShopPause(data: ShopPauseRequest): Promise<ShopPauseResult> {
  return request<ShopPauseResult>({ url: '/admin/shop/pause', method: 'put', data })
}

/**
 * 发布 / 撤下营业公告（T62，F06）。
 *
 * 与暂停开关解耦：切开关不再覆盖公告。响应回归一化后的值，前端据此回填输入框。
 */
export function updateShopNotice(notice: string | null): Promise<ShopNoticeResult> {
  return request<ShopNoticeResult>({ url: '/admin/shop/notice', method: 'put', data: { notice } })
}

/** PUT /api/admin/shop/sla 请求体（T52）：SLA 预警阈值（秒） */
export interface SlaSettingsRequest {
  warnSeconds: number
  /** 须大于 warnSeconds，否则后端返回 1001 */
  dangerSeconds: number
}

/** PUT /api/admin/shop/sla 响应 data：写入后的生效阈值 */
export interface SlaSettingsResult {
  warnSeconds: number
  dangerSeconds: number
}

/**
 * 设置看板 SLA 预警阈值（T52）。
 *
 * 写入 shop_config 后随看板响应即时下发，因此保存后下一次刷新即生效
 * （验收项「阈值改动后预警即时生效」）。
 */
export function updateSla(data: SlaSettingsRequest): Promise<SlaSettingsResult> {
  return request<SlaSettingsResult>({ url: '/admin/shop/sla', method: 'put', data })
}
