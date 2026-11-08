import type { AiDraft } from '@/api/ai'
import { openSse, type SseHandle } from '@/utils/sse'

/**
 * AI 流式对话接口（`POST /api/customer/ai/chat/stream`）。
 *
 * <p>事件契约与后端 `AiStreamEvent` 一一对应（前后端唯一真相）：</p>
 *
 * | event | 载荷 | 前端动作 |
 * | --- | --- | --- |
 * | `stage` | `{stage}` | 点亮阶段文案 |
 * | `delta` | `{text}` | 增量追加到助手气泡 |
 * | `decision` | `{source, confidence, productCode}` | 记录来源（可选提示） |
 * | `card` | `{items, totalAmount}` | 挂草稿卡并触发语音播报 |
 * | `done` | `{sessionId, replyType}` | 收尾，恢复输入 |
 * | `error` | `{code, message, fallbackText}` | 复用既有错误处理 |
 *
 * <p><b>有传输层错误与业务层错误两种失败</b>，必须分开处理：前者（网络断了、401）
 * 应当回退到非流式接口，后者（1008 模型不可用）应当直接展示降级横幅——混在一起会让
 * 「模型挂了」被当成「网络不好」重试一遍。</p>
 */

/** 决策阶段文案的三个档（与后端 `AiStreamEvent.STAGE_*` 对应） */
export type AiStreamStage = 'UNDERSTAND' | 'PICK' | 'PRICE'

/** 决策来源：Jev 决策层 / 既有 GLM 链路（含降级） */
export type AiDecisionSource = 'JEV' | 'GLM_FALLBACK'

/** `decision` 事件载荷 */
export interface AiStreamDecision {
  source: AiDecisionSource
  confidence: number
  productCode: string | null
}

/** `done` 事件载荷 */
export interface AiStreamDone {
  sessionId: string
  replyType: 'TEXT' | 'CARD'
}

/** `error` 事件载荷（`fallbackText` 与既有 LLD 3.4 非流式降级形态一致） */
export interface AiStreamError {
  code: number
  message: string
  fallbackText: string | null
}

/** 流式回调集合 */
export interface AiStreamHandlers {
  onStage?: (stage: AiStreamStage) => void
  onDelta?: (text: string) => void
  onDecision?: (decision: AiStreamDecision) => void
  onCard?: (draft: AiDraft) => void
  onDone?: (payload: AiStreamDone) => void
  onError?: (payload: AiStreamError) => void
  /** 传输层结束但没收到 `done`/`error`（服务端超时、连接被断）：调用方应回退非流式接口 */
  onInterrupted?: () => void
  /** 传输层失败（网络异常 / 非 2xx）：同样回退非流式接口 */
  onTransportError?: (error: Error) => void
}

/**
 * 发起一轮流式对话。
 *
 * @param sessionId 会话标识；首轮传 null，后端新建并通过 `done` 回传
 * @param message   顾客输入
 * @param handlers  事件回调
 * @returns 通道句柄（页面卸载或重新提问时 abort）
 */
export function chatStream(
  sessionId: string | null,
  message: string,
  handlers: AiStreamHandlers
): Promise<SseHandle> {
  let settled = false

  return openSse({
    url: '/api/customer/ai/chat/stream',
    data: { sessionId: sessionId || undefined, message },
    onEvent: (name, data) => {
      switch (name) {
        case 'stage':
          handlers.onStage?.(payloadOf<{ stage: AiStreamStage }>(data).stage)
          break
        case 'delta':
          handlers.onDelta?.(payloadOf<{ text: string }>(data).text)
          break
        case 'decision':
          handlers.onDecision?.(payloadOf<AiStreamDecision>(data))
          break
        case 'card': {
          const card = payloadOf<{ items: AiDraft['items']; totalAmount: string }>(data)
          handlers.onCard?.({ items: card.items, totalAmount: card.totalAmount })
          break
        }
        case 'done':
          settled = true
          handlers.onDone?.(payloadOf<AiStreamDone>(data))
          break
        case 'error':
          settled = true
          handlers.onError?.(payloadOf<AiStreamError>(data))
          break
        default:
          // 没有 event 名的帧只可能是「非流式响应体被当成事件流收到」——典型是 401 的 JSON。
          // 在这里把它识别成错误，比让它静默消失更容易排查。
          if (!name && data) {
            settled = true
            handlers.onError?.(errorPayloadOf(data))
          }
          break
      }
    },
    onClose: () => {
      // 事件流正常关闭却没发过 done/error：说明是服务端超时或连接中断
      if (!settled) {
        handlers.onInterrupted?.()
      }
    },
    onFail: (error) => {
      if (!settled) {
        handlers.onTransportError?.(error)
      }
    }
  })
}

/** 解析事件载荷；坏数据返回空对象而不是抛错（一帧坏数据不该炸掉整轮流式对话）。 */
function payloadOf<T>(data: string): T {
  try {
    return JSON.parse(data) as T
  } catch {
    console.error('流式事件解析失败', data)
    return {} as T
  }
}

/** 把「不带 event 名的 JSON 帧」解读成错误载荷。 */
function errorPayloadOf(data: string): AiStreamError {
  const body = payloadOf<{ code?: number; message?: string; data?: { fallbackText?: string } }>(data)
  return {
    code: body.code ?? -1,
    message: body.message || 'AI 助手暂时不可用',
    fallbackText: body.data?.fallbackText ?? null
  }
}
