/**
 * 金额工具（T20 柜台点单）：后端契约金额为两位小数字符串（LLD 3.1），
 * 前端本地合计一律换算为「分」的整数运算，避免浮点误差——
 * 仅用于收银台展示，最终金额以后端计算为准（下单接口返回实付金额）。
 */

/** "12.00" / 12 → 1200（分）。非法输入按 0 分计。 */
export function toCents(amount: string | number | null | undefined): number {
  if (amount === null || amount === undefined) return 0
  const value = typeof amount === 'number' ? amount : Number.parseFloat(amount)
  if (!Number.isFinite(value)) return 0
  return Math.round(value * 100)
}

/** 分 → "12.00"（两位小数字符串）。 */
export function formatCents(cents: number): string {
  return (cents / 100).toFixed(2)
}
