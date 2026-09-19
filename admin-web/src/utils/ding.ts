/**
 * 新单提示音（T19）：Web Audio API 实时合成「叮-咚」双音，零素材依赖。
 *
 * 【施工登记】任务卡涉及文件列出 assets/ding.mp3 提示音素材；本项目不引入二进制素材，
 * 以代码合成等效提示音（LLD 7.3 仅约定「新增待制作单时播放提示音」，未约束素材形态）。
 *
 * 浏览器自动播放策略：AudioContext 默认 suspended，必须在用户手势（点击/按键）后
 * 才能播放声音——进入看板页时调用一次 {@link unlockDing} 解锁，此后轮询触发的新单
 * 提示音即可正常播放。
 */

let ctx: AudioContext | null = null

/** 懒创建 AudioContext；缺失 Web Audio 的环境返回 null（静默降级，不影响看板功能）。 */
function ensureContext(): AudioContext | null {
  if (typeof window === 'undefined') return null
  const ctor =
    window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!ctor) return null
  ctx ??= new ctor()
  return ctx
}

/** 用户手势后调用：解锁自动播放限制（看板 onMounted 时执行一次）。 */
export function unlockDing(): void {
  const c = ensureContext()
  if (c && c.state === 'suspended') {
    void c.resume()
  }
}

/** 播放新单「叮-咚」提示音（约 0.4s，E6 → B5 下行双音）。 */
export function playDing(): void {
  const c = ensureContext()
  if (!c) return
  if (c.state === 'suspended') {
    void c.resume()
  }
  const now = c.currentTime
  beep(c, 1318.51, now, 0.16)
  beep(c, 987.77, now + 0.17, 0.2)
}

/** 合成单个正弦音符：15ms 快速起音 + 指数衰减，近似门店出单提示音。 */
function beep(c: AudioContext, frequency: number, startAt: number, duration: number): void {
  const osc = c.createOscillator()
  const gain = c.createGain()
  osc.type = 'sine'
  osc.frequency.value = frequency
  gain.gain.setValueAtTime(0.0001, startAt)
  gain.gain.exponentialRampToValueAtTime(0.35, startAt + 0.015)
  gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration)
  osc.connect(gain)
  gain.connect(c.destination)
  osc.start(startAt)
  osc.stop(startAt + duration + 0.02)
}
