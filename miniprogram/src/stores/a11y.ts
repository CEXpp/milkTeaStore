import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 无障碍模式（T45，SRS 9.6 / LLD 11.4）——顾客端呈现层开关。
 *
 * <p>设计纪律「共享逻辑、分叉呈现」：本开关<b>只影响样式</b>（页面根节点挂 {@code a11y-mode}
 * class），不新增后端接口、不改变任何状态机的判定与落库。取餐码、订单状态、队列预估全部来自
 * 同一套后端契约——无障碍模式只是把它们<b>放大、加黑、并追加震动信道</b>给用户。</p>
 *
 * <p>偏好存本地 storage：重新进入小程序仍保持（用户不必每次重新开启）。</p>
 */

const STORAGE_KEY = 'customer_a11y_mode'

export const useA11yStore = defineStore('a11y', () => {
  /** 是否开启无障碍模式（首次读取本地持久化偏好） */
  const enabled = ref(uni.getStorageSync(STORAGE_KEY) === '1')

  function setEnabled(value: boolean): void {
    enabled.value = value
    uni.setStorageSync(STORAGE_KEY, value ? '1' : '0')
    // 触觉反馈：让用户（尤其是视障用户）明确感知「开关已生效」，无需依赖视觉确认
    vibrateShortSafely()
  }

  function toggle(): void {
    setEnabled(!enabled.value)
  }

  return { enabled, setEnabled, toggle }
})

/** 短震动反馈；宿主不支持（H5 / 部分机型）或用户关闭震动时静默忽略。 */
function vibrateShortSafely(): void {
  try {
    uni.vibrateShort({ fail: () => undefined })
  } catch {
    // 忽略：反馈只是增强，不能因它报错打断开关操作
  }
}
