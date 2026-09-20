import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { formatCents } from '@/utils/money'

/**
 * 购物车 store（T24，Pinia）：菜单页加购 → 购物车页结算共用同一份状态。
 *
 * 说明：
 * - 每行记录「商品 + 规格组合 + 口味备注」，同一商品不同规格算不同行，规格完全相同的行合并数量；
 * - `unitCents` 为本地展示价（基础价 + 价差，按分累加），**仅供展示**——
 *   下单与实付金额一律由后端计价引擎计算（LLD 4.5）。
 */
export interface CartItem {
  /** 本地行 key（行内唯一） */
  key: string
  productId: number
  productName: string
  imageUrl?: string
  /** 规格选项 id（提交下单用） */
  optionIds: number[]
  /** 规格名文本（展示用，如「大杯/少冰/珍珠」） */
  specText: string
  /** 本地展示单杯价（分） */
  unitCents: number
  quantity: number
  /** 口味备注（选填，随订单提交） */
  remark?: string
}

/** 加购入参（key 由 store 生成） */
export type CartItemInput = Omit<CartItem, 'key'>

/** 单行最大杯数（与后端 PricingService 的 quantity 上限 1..20 对齐） */
export const MAX_QUANTITY = 20

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])

  /** 商品总杯数（购物车浮动球角标） */
  const totalQuantity = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))

  /** 本地合计金额（分，仅展示） */
  const totalCents = computed(() => items.value.reduce((sum, item) => sum + item.unitCents * item.quantity, 0))

  /** 本地合计金额（两位小数字符串，仅展示） */
  const totalAmount = computed(() => formatCents(totalCents.value))

  /** 是否为空 */
  const isEmpty = computed(() => items.value.length === 0)

  let seq = 0

  /** 规格完全一致（同商品 + 同选项集合 + 同备注）视为同一行，合并数量 */
  function sameLine(item: CartItem, input: CartItemInput): boolean {
    return (
      item.productId === input.productId &&
      item.remark === input.remark &&
      item.optionIds.length === input.optionIds.length &&
      [...item.optionIds].sort().join(',') === [...input.optionIds].sort().join(',')
    )
  }

  /** 加购：命中已有行则累加数量（上限 20 杯），否则追加新行 */
  function add(input: CartItemInput): void {
    const quantity = Math.max(1, Math.min(MAX_QUANTITY, input.quantity || 1))
    const existing = items.value.find((item) => sameLine(item, input))
    if (existing) {
      existing.quantity = Math.min(MAX_QUANTITY, existing.quantity + quantity)
      return
    }
    seq += 1
    items.value.push({ ...input, quantity, key: `cart-${seq}` })
  }

  /** 删除行 */
  function remove(key: string): void {
    items.value = items.value.filter((item) => item.key !== key)
  }

  /** 数量步进（范围 1..20；减到 0 不自动删除，由界面上的删除按钮负责） */
  function updateQty(key: string, delta: number): void {
    const item = items.value.find((line) => line.key === key)
    if (!item) return
    item.quantity = Math.max(1, Math.min(MAX_QUANTITY, item.quantity + delta))
  }

  /** 设置行备注 */
  function setRemark(key: string, remark: string): void {
    const item = items.value.find((line) => line.key === key)
    if (item) item.remark = remark
  }

  /** 清空（下单成功后调用） */
  function clear(): void {
    items.value = []
  }

  /** 下单请求体（仅商品/规格/数量，不含金额——金额由后端计算） */
  function toOrderItems(): Array<{ productId: number; optionIds: number[]; quantity: number }> {
    return items.value.map((item) => ({
      productId: item.productId,
      optionIds: [...item.optionIds],
      quantity: item.quantity
    }))
  }

  /** 整单备注：各行备注聚合（后端只收一个 remark 字段，LLD 3.3） */
  function orderRemark(): string {
    return items.value
      .filter((item) => item.remark && item.remark.trim())
      .map((item) => `${item.productName}：${item.remark?.trim()}`)
      .join('；')
  }

  return {
    items,
    totalQuantity,
    totalCents,
    totalAmount,
    isEmpty,
    add,
    remove,
    updateQty,
    setRemark,
    clear,
    toOrderItems,
    orderRemark
  }
})
