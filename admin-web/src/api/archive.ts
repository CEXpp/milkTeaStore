import { request, type PageQuery, type PageResult } from '@/utils/request'
import type { OrderSource, OrderStatus } from './order'

/**
 * 每一杯茶的制作档案（T59，W21）。
 *
 * **只读**：本页所有字段都取自下单瞬间锁定的快照，商品后续改价改规格不会回溯到历史档案
 * （与 6.3「价格不回溯」同源，扩展为「整份档案不回溯」）。前端无任何编辑入口，
 * 后端亦不存在修改档案的接口。
 */

/** 一行规格（快照原样呈现） */
export interface BrewArchiveOption {
  /** 规格组名：杯型 / 温度 / 甜度 / 加料 */
  groupName: string
  optionName: string
  /** 价差（两位小数字符串） */
  priceDelta: string | null
}

/** 一份档案 = 一个订单项 = 「每一杯」（同项多杯共用一份） */
export interface BrewArchive {
  /** 订单项主键，档案的稳定标识 */
  orderItemId: number
  orderId: number
  orderNo: string
  /** 取餐码；未支付单为 null */
  pickupCode: string | null
  source: OrderSource
  status: OrderStatus
  /** 商品 id 指向当前商品（可能已下架/改名），与 productName 快照不一致是正常的 */
  productId: number
  /** 商品名快照（商品改名后仍为下单时的旧名） */
  productName: string
  /** 杯数 */
  quantity: number
  basePrice: string
  /** 单价快照 = 基础价 + Σ 价差 */
  unitPrice: string
  /** 单项金额快照 = 单价 × 杯数 */
  itemAmount: string
  /** 规格快照逐行明细（核对以此为准） */
  options: BrewArchiveOption[]
  /** 规格一行摘要，仅供列表速览，不得作为核对依据 */
  optionSummary: string | null
  remark: string | null
  /** 结构化备注标签条数（T53 落地后有值；只报条数不解释语义） */
  remarkTagCount: number
  /** 作废原因；仅作废单有值 */
  voidReason: string | null
  /** 六个时间戳：未发生的节点为 null */
  createdAt: string
  paidAt: string | null
  startedAt: string | null
  completedAt: string | null
  closedAt: string | null
  voidedAt: string | null
  /** 本单制作耗时（分钟）；未完成为 null */
  prepMinutes: number | null
  /** 归属日（锚点为 paid_at，未支付单回落到 created_at） */
  belongDate: string
}

/**
 * GET /api/admin/brew-archives 查询参数。
 *
 * 日期三选一：`date`（单日）、`from` + `to`（左闭右开，需成对）、全空（不限日期）。
 */
export interface BrewArchiveQuery extends PageQuery {
  /** yyyy-MM-dd 单日 */
  date?: string
  /** 起始日（含） */
  from?: string
  /** 结束日（不含） */
  to?: string
  /** 商品 id 精确 */
  productId?: number
  /** 商品名模糊（匹配快照名，非当前商品名） */
  productName?: string
  /** 规格模糊：匹配规格组名 / 选项名 / 加料名 */
  spec?: string
  status?: OrderStatus
}

/** 检索制作档案（分页） */
export function getBrewArchives(params: BrewArchiveQuery = {}): Promise<PageResult<BrewArchive>> {
  return request<PageResult<BrewArchive>>({ url: '/admin/brew-archives', method: 'get', params })
}

/** 单杯完整档案：六个时间戳 + 规格快照 + 金额 + 备注 + 作废原因一次给全 */
export function getBrewArchive(orderItemId: number): Promise<BrewArchive> {
  return request<BrewArchive>({ url: `/admin/brew-archives/${orderItemId}`, method: 'get' })
}