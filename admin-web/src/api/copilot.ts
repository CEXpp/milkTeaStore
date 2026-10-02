import { request } from '@/utils/request'

/**
 * 店长 Copilot 接口（T67，W07）。
 *
 * Copilot **只有查询权限**：没有任何修改数据的接口。越权提问由后端两道拦截拒掉
 * （工具集里没有写工具 + 未登记工具名一律改写为拒绝语），前端据此提示。
 */

/** 一次工具查询的原始结果（折叠面板的数据源） */
export interface CopilotToolResult {
  /** 工具名，如 dailySummary */
  tool: string
  /** 中文标题，如「当日经营概览」 */
  title: string
  /** 入参原文，如 {"date":"2026-10-02"} */
  args: string | null
  /** 工具返回原文——不是模型复述，因此数字必然可在报表复现 */
  data: string
}

/** POST /api/admin/copilot/chat 请求体 */
export type CopilotChatRequest = {
  /** 会话标识；空表示新会话（多轮追问时回传上一轮的值） */
  conversationId?: string
  /** 店长的自然语言提问 */
  question: string
}

/** POST /api/admin/copilot/chat 响应 data */
export interface CopilotAnswer {
  /** 会话标识：多轮追问时回传，无此值则每轮都是独立问答 */
  sessionId: string
  /** 结论文本；模型不可用时为 null */
  conclusion: string | null
  /** 是否降级（模型不可用）：此时只回提示，不编造结论 */
  degraded: boolean
  /** 降级或越权时的说明文案 */
  tip: string | null
  /** 本轮是否出现越权拦截 */
  rejected: boolean
  /** 工具返回的原始数据表格（默认折叠） */
  tables: CopilotToolResult[]
}

/** 一轮经营问答：结论 + 可折叠的原始数据表格 */
export function askCopilot(data: CopilotChatRequest): Promise<CopilotAnswer> {
  return request<CopilotAnswer>({ url: '/admin/copilot/chat', method: 'post', data })
}
