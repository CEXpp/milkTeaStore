import { request } from '@/utils/request'

/**
 * AI 点单接口（T33，LLD 3.4）。
 *
 * - `POST /api/customer/ai/chat`：对话。首轮不带 sessionId，后端新建会话并在响应里回传；
 * - `POST /api/customer/ai/confirm-order`：草稿转正式订单（PENDING_PAYMENT），
 *   前端拿到 orderId 后立刻连发 pay 完成支付闭环。
 *
 * **AI 侧没有支付能力**：本文件只到 confirm-order 为止，付钱一律由用户点「立即支付」触发
 * （SRS 约束三原则第三条）。
 */

/** 错误码：草稿单为空（LLD 3.2） */
export const CODE_DRAFT_EMPTY = 1007
/** 错误码：AI 服务不可用（LLD 3.2，响应 data.fallbackText 给兜底话术） */
export const CODE_AI_UNAVAILABLE = 1008
/** 错误码：请求过于频繁（LLD 3.2，按顾客限流 10 次/分钟） */
export const CODE_RATE_LIMITED = 1009

/** 回复形态：纯文本 / 草稿卡片 */
export type AiReplyType = 'TEXT' | 'CARD'

/** 草稿单条目（金额为两位小数字符串，由后端计价引擎计算） */
export interface AiDraftItem {
  productName: string
  optionNames: string[]
  quantity: number
  unitPrice: string
  itemAmount: string
}

/** 草稿单（LLD 3.4 CARD 形态） */
export interface AiDraft {
  items: AiDraftItem[]
  totalAmount: string
}

/** POST /api/customer/ai/chat 响应 data */
export interface AiChatResult {
  sessionId: string
  replyType: AiReplyType
  text: string
  /** TEXT 形态为 null */
  draft: AiDraft | null
}

/** AI 不可用时的降级数据体（LLD 3.4：code=1008 + data.fallbackText） */
export interface AiFallback {
  fallbackText: string
}

/** POST /api/customer/ai/confirm-order 响应 data（LLD 3.4） */
export interface AiConfirmResult {
  orderId: number
  orderNo: string
  totalAmount: string
}

/**
 * 一轮对话（POST /api/customer/ai/chat）。
 *
 * @param sessionId 会话标识；首轮传空，后端新建并回传
 * @param message   顾客输入（输入法语音转文字或手打）
 */
export function aiChat(sessionId: string | null, message: string): Promise<AiChatResult> {
  return request<AiChatResult>({
    url: '/api/customer/ai/chat',
    method: 'POST',
    data: { sessionId: sessionId || undefined, message },
    auth: true
  })
}

/**
 * 草稿转正式订单（POST /api/customer/ai/confirm-order）。
 *
 * @param sessionId 会话标识（草稿挂在会话上，商品与规格一律以服务端草稿为准）
 */
export function confirmAiOrder(sessionId: string): Promise<AiConfirmResult> {
  return request<AiConfirmResult>({
    url: '/api/customer/ai/confirm-order',
    method: 'POST',
    data: { sessionId },
    auth: true
  })
}
