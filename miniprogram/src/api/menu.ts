import { request } from '@/utils/request'

/**
 * 顾客端菜单与门店状态接口（公开接口，无需 token）。
 * 契约：LLD 3.3 GET /api/customer/menu、GET /api/customer/shop-status。
 */

/** 规格选项（菜单视图：含价差） */
export interface MenuSpecOption {
  id: number
  name: string
  /** 价差（两位小数字符串） */
  priceDelta: string
}

/** 规格组（菜单视图） */
export interface MenuSpecGroup {
  /** 组编码：CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING */
  code: string
  name: string
  /** true=多选（加料），false=单选（杯型/温度/甜度） */
  multiSelect: boolean
  options: MenuSpecOption[]
}

/** 上架商品（菜单视图） */
export interface MenuProduct {
  id: number
  name: string
  description: string
  imageKey: string
  imageUrl: string
  /** 基础价（两位小数字符串） */
  basePrice: string
  specGroups: MenuSpecGroup[]
}

/** 分类（含上架商品） */
export interface MenuCategory {
  id: number
  name: string
  sortOrder: number
  products: MenuProduct[]
}

/** GET /api/customer/menu 响应 data */
export interface MenuResult {
  /** 暂停营业标志（LLD 3.3：暂停时仍可浏览菜单，前端展示遮罩 / 横幅） */
  paused: boolean
  categories: MenuCategory[]
}

/** GET /api/customer/shop-status 响应 data */
export interface ShopStatus {
  paused: boolean
  notice: string | null
}

/** 全量菜单（分类 + 上架商品 + 适用规格组与选项价差） */
export function getMenu(): Promise<MenuResult> {
  return request<MenuResult>({ url: '/api/customer/menu' })
}

/** 门店营业状态（暂停接单遮罩的数据源） */
export function getShopStatus(): Promise<ShopStatus> {
  return request<ShopStatus>({ url: '/api/customer/shop-status' })
}
