import { request } from '@/utils/request'

/**
 * PUT /api/admin/shop/pause 请求体。
 * 开启暂停后顾客端下单返回 1006；进行中订单不受影响（LLD 3.5.5）。
 */
export interface ShopPauseRequest {
  paused: boolean
  /** 暂停提示语（顾客端展示，如“高峰期制作中，稍后开放点单”） */
  notice?: string
}

/** PUT /api/admin/shop/pause 响应 data */
export interface ShopPauseResult {
  paused: boolean
}

/** 门店开关：暂停 / 恢复接单（/settings 页数据源） */
export function updateShopPause(data: ShopPauseRequest): Promise<ShopPauseResult> {
  return request<ShopPauseResult>({ url: '/admin/shop/pause', method: 'put', data })
}
