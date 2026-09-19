/**
 * 柜台点单本地类型（非后端契约，仅收银台页面内使用）。
 */

/** 草稿行：一次「商品 + 规格组合 + 数量」，可重复加购同一组合 */
export interface DraftLine {
  /** 行唯一键（增删改定位用） */
  key: string
  productId: number
  productName: string
  optionIds: number[]
  /** 规格名列表（展示串 `specText` 的生成源） */
  optionNames: string[]
  /** 规格展示串，如 "大杯/少冰/全糖/珍珠" */
  specText: string
  /** 单杯价（分；本地展示计算，实付以后端计价结果为准） */
  unitCents: number
  quantity: number
}
