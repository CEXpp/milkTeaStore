/**
 * 金额工具：接口金额一律为两位小数字符串（LLD 3.1），前端本地展示价按「分」累加，
 * 避免浮点误差；实付金额始终以后端返回为准（LLD 4.5 唯一算价入口）。
 */

/** 金额字符串（"12.00"）→ 分（1200）；非法值按 0 处理 */
export function toCents(price: string | number | null | undefined): number {
  const value = Number(price ?? 0)
  return Number.isFinite(value) ? Math.round(value * 100) : 0
}

/** 分 → 展示金额字符串（1200 → "12.00"） */
export function formatCents(cents: number): string {
  return (Math.round(cents) / 100).toFixed(2)
}
