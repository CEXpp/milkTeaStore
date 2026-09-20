/**
 * 订单状态文案与判定（LLD 4.1 状态机 / 4.4 轮询）。
 *
 * 状态枚举：PENDING_PAYMENT（待支付）、PAID（已支付待制作）、PREPARING（制作中）、
 * COMPLETED（已完成）、CLOSED（超时关闭）、VOIDED（已作废）。
 */

/** 进行中状态集合（订单 Tab「进行中」区与轮询判定用） */
const ACTIVE_STATUSES = ['PENDING_PAYMENT', 'PAID', 'PREPARING']

/** 状态基础文案 */
export const STATUS_TEXT: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  PAID: '排队中',
  PREPARING: '制作中',
  COMPLETED: '请取餐',
  CLOSED: '已关闭',
  VOIDED: '已作废'
}

/** 状态徽标配色（对应 el-tag 语义：warning / primary / success / info / danger） */
export const STATUS_TYPE: Record<string, string> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'primary',
  PREPARING: 'primary',
  COMPLETED: 'success',
  CLOSED: 'info',
  VOIDED: 'danger'
}

/** 是否进行中（决定是否继续 3 秒轮询） */
export function isActiveStatus(status: string | null | undefined): boolean {
  return status ? ACTIVE_STATUSES.includes(status) : false
}

/** 是否终态 */
export function isTerminalStatus(status: string | null | undefined): boolean {
  return status === 'COMPLETED' || status === 'CLOSED' || status === 'VOIDED'
}

/**
 * 状态展示文案（任务卡 T27）：
 * PAID → 「排队中第 N 位」（N = seq + 1，seq 为该单之前处于 PAID/PREPARING 的单数）、
 * PREPARING → 「制作中」、COMPLETED → 「请取餐」。
 */
export function statusLabel(status: string | null | undefined, seq?: number | null): string {
  if (!status) return ''
  if (status === 'PAID') {
    return typeof seq === 'number' && seq >= 0 ? `排队中第 ${seq + 1} 位` : '排队中'
  }
  return STATUS_TEXT[status] ?? status
}
