import { request } from '@/utils/request'

/**
 * 顾客菜单接口（公开接口，服务员柜台点单复用其数据：分类 / 上架商品 / 适用规格组与价差）。
 * 契约：LLD 3.3 GET /api/customer/menu。
 */

/** 规格选项（菜单视图：含价差） */
export interface MenuSpecOption {
  id: number
  name: string
  /** 价差（两位小数字符串，如 "3.00"） */
  priceDelta: string
}

/** 规格组（菜单视图） */
export interface MenuSpecGroup {
  /** 组编码：CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING */
  code: string
  name: string
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
  /** 暂停营业标志（柜台人工点单不受其限制，仅作提示展示） */
  paused: boolean
  categories: MenuCategory[]
}

/** 全量菜单（分类 + 上架商品 + 适用规格组与选项价差） */
export function getMenu(): Promise<MenuResult> {
  return request<MenuResult>({ url: '/customer/menu', method: 'get' })
}
