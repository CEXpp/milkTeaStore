import type { AiDraft } from '@/api/ai'

/**
 * AI 语音应答（T70，W10 · D5b）：把算好的草稿单念出来。
 *
 * <p><b>与附录 A 的边界（必须说清，否则会被误读成推翻 ADR）</b>：
 * 附录 A 排除的是「自研 ASR（<b>输入侧</b>）」，而本需求在输入侧<b>完全不碰 ASR</b>
 * ——顾客仍然用输入法/键盘麦克风把字打出来。本模块只增加<b>输出侧</b>的 TTS：
 * 屏幕上看得到的文字照旧有一份，语音只是把同一份内容再读一遍。</p>
 *
 * <p><b>播报内容与卡片严格同源</b>（验收项「金额必须与后端计算一致」）：
 * 播报文本由 {@link buildSpeechText} 从<b>后端返回的草稿单字段</b>拼出，
 * 不做任何二次计算。卡片显示 18.50，念出来也是 18.50——因为两者读的是同一个
 * {@code draft.totalAmount}，而不是「前端算一遍给卡片、模型算一遍给语音」。</p>
 *
 * <p><b>TTS 不可用时静默降级为纯文本</b>（验收项）：本模块不抛异常、不弹提示。
 * 微信小程序没有内置 TTS，需要插件或平台能力；拿不到就当没有这个功能，
 * 屏幕上的卡片与文字一个不少。</p>
 */

/** 播报引擎句柄：由具体平台实现（插件 / H5 Web Speech）。 */
type SpeakEngine = (text: string) => void

let engine: SpeakEngine | null = null
let engineResolved = false

/**
 * 解析可用的 TTS 引擎。<b>只解析一次</b>并缓存结果：
 * 探测本身可能有开销（读插件管理器、读全局对象），而每轮对话都探测一次很浪费。
 */
function resolveEngine(): SpeakEngine | null {
  if (engineResolved) {
    return engine
  }
  engineResolved = true

  // 1) 微信同声传译插件（小程序侧唯一可用的 TTS 通道）。
  //    插件需在 app.json 的 plugins 里声明并经用户同意授权，故这里做存在性判断即可，
  //    未声明/未授权时 requirePlugin 会抛，落到下面的 H5 分支或 null。
  try {
    const plugin = (uni as unknown as { requirePlugin?: (p: unknown) => { textToSpeech?: (o: object) => void } })
      .requirePlugin?.('WechatSI')
    if (plugin?.textToSpeech) {
      engine = (text: string) => {
        plugin.textToSpeech!({
          lang: 'zh_CN',
          tts: true,
          content: text,
          success: () => undefined,
          // 播报失败对用户不可见也不可救，静默即可——卡片上的文字已经是对的
          fail: () => undefined
        })
      }
      return engine
    }
  } catch {
    // 未声明插件 / 用户未授权：继续尝试下一种通道
  }

  // 2) H5 侧 Web Speech API（uni-app 预览 / 浏览器联调时可用）
  const speech = (globalThis as { speechSynthesis?: SpeechSynthesis }).speechSynthesis
  if (speech && typeof globalThis.SpeechSynthesisUtterance === 'function') {
    engine = (text: string) => {
      try {
        const utterance = new globalThis.SpeechSynthesisUtterance(text)
        utterance.lang = 'zh-CN'
        speech.cancel()
        speech.speak(utterance)
      } catch {
        // 同上：播报是增强，失败不影响主流程
      }
    }
    return engine
  }

  engine = null
  return engine
}

/** 当前环境是否具备 TTS 能力（界面据此决定要不要显示「语音播报」开关）。 */
export function isSpeechAvailable(): boolean {
  return resolveEngine() !== null
}

/**
 * 播报一段文本。
 *
 * @param text 播报内容
 * @returns 是否真的播报了（false = 不可用或已关闭，调用方无需任何处理）
 */
export function speak(text: string): boolean {
  if (!text) {
    return false
  }
  const resolved = resolveEngine()
  if (!resolved) {
    return false
  }
  try {
    resolved(text)
    return true
  } catch {
    return false
  }
}

/** 停止播报（用户手动关闭时调用，避免已经排队的语音继续念）。 */
export function stopSpeaking(): void {
  try {
    const speech = (globalThis as { speechSynthesis?: SpeechSynthesis }).speechSynthesis
    speech?.cancel()
  } catch {
    // 忽略
  }
}

/**
 * 由草稿单拼出播报文本。
 *
 * <p><b>只读后端字段，不做任何计算</b>（验收项「金额必须与后端计算一致」）：
 * 合计直接念 {@code draft.totalAmount}，不把各行小计加起来——那样万一某行
 * 因故拿不到，卡片与语音就会差钱，而用户听到的是错的数字。</p>
 *
 * <p>播报长度有上限：微信 TTS 对长文本会截断，而截断在一句中间很难听懂。
 * 故单品念「商品名 + 规格 + 杯数」，合计单独念；条目过多时只念前几项并说明还有几项。</p>
 *
 * @param draft 后端返回的草稿单
 * @returns 播报文本；草稿为空时返回空串
 */
export function buildSpeechText(draft: AiDraft): string {
  if (!draft?.items?.length) {
    return ''
  }
  const MAX_ITEMS_SPOKEN = 3
  const spoken = draft.items.slice(0, MAX_ITEMS_SPOKEN)
  const parts: string[] = []

  spoken.forEach((item) => {
    const specs = item.optionNames?.length ? item.optionNames.join('、') : '默认规格'
    const name = item.quantity > 1 ? `${item.productName} ${item.quantity} 杯` : item.productName
    parts.push(`${name}，${specs}`)
  })

  if (draft.items.length > MAX_ITEMS_SPOKEN) {
    parts.push(`等 ${draft.items.length} 项`)
  }

  parts.push(`合计 ${draft.totalAmount} 元`)
  return `好的，${parts.join('；')}。`
}
