import { ref } from 'vue'
import { defineStore } from 'pinia'
import { isSpeechAvailable, speak, stopSpeaking } from '@/utils/speech'

/**
 * 语音播报开关（T70，W10 · D5b）。
 *
 * <p><b>默认开启但可一键关闭</b>：播报本身是为「看屏幕不方便」的用户准备的便利，
 * 对另一些人却是打扰（店里吵、正在通话）。所以它必须是一个用户能自己关掉的开关，
 * 而不是系统替所有人决定。</p>
 *
 * <p><b>偏好存本地</b>：与 T45 无障碍模式同一理由——这是纯呈现层偏好，
 * 服务端不感知、不入库，也<b>不改变任何订单数据与统计口径</b>。</p>
 *
 * <p><b>能力不足时开关不出现</b>：环境没有 TTS 通道时，界面不显示这个开关
 * （而不是显示一个点了没反应的开关）。</p>
 */

const STORAGE_KEY = 'customer_speech_enabled'

export const useSpeechStore = defineStore('speech', () => {
  /** 是否开启语音播报（首次读取本地持久化偏好） */
  const enabled = ref(uni.getStorageSync(STORAGE_KEY) === '1')
  /** 当前环境是否具备 TTS 能力；false 时界面不展示开关 */
  const available = ref(false)

  /** 探测一次能力并据首访偏好初始化 */
  function init(): void {
    available.value = isSpeechAvailable()
    if (!available.value) {
      // 没有通道就别让偏好值说谎：开关既不可用也不该是「开」
      enabled.value = false
      return
    }
    if (uni.getStorageSync(STORAGE_KEY) === undefined) {
      // 首次使用：默认开启。播报只发生在 AI 解析完成的瞬间，频率低、时长短。
      enabled.value = true
      uni.setStorageSync(STORAGE_KEY, '1')
    }
  }

  function setEnabled(value: boolean): void {
    enabled.value = value
    uni.setStorageSync(STORAGE_KEY, value ? '1' : '0')
    // 关闭时立刻停掉正在播的语音，否则用户点了关闭还要把这句话听完
    if (!value) {
      stopSpeaking()
    }
  }

  function toggle(): void {
    setEnabled(!enabled.value)
  }

  /**
   * 播报一段文本（已内建「关闭中不播报」的判断）。
   *
   * @returns 是否真的播报了
   */
  function say(text: string): boolean {
    if (!available.value || !enabled.value || !text) {
      return false
    }
    return speak(text)
  }

  return { enabled, available, init, toggle, setEnabled, say }
})
