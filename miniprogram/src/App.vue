<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { getActiveOrders } from '@/api/order'
import { login } from '@/utils/request'

/**
 * 应用入口（T27 · v0.2）：
 * - T24：启动时静默登录换取顾客 JWT（LLD 8.3）；菜单等公开接口不依赖 token；
 * - T27：**再扫码**进小程序时自动恢复进行中订单——存在进行中订单则定位到订单 Tab，
 *   顾客可立刻看到取餐码与制作进度（LLD 8.2「再扫码进小程序自动恢复进行中订单」）。
 *
 * v0.2 修复（启动落点异常）：
 *   原实现对**任何冷启动**都执行「恢复并切订单 Tab」，而订单页有请求，启动瞬间被切走会导致
 *   菜单主页始终不可见（现象：打开小程序直接落在订单页，导航栏显示「我的订单」）。
 *   现按 LLD 8.2 原文收敛触发范围——**仅扫码类场景进入**才恢复跳转；普通冷启动 / 从「最近使用」
 *   进入停留在菜单主页，进行中订单仍可在订单 Tab 查看（能力不丢失）。
 */

/**
 * 扫码类场景值（微信小程序场景值）：
 * 1011 扫描二维码、1012 长按图片识别二维码、1013 手机相册选取二维码、
 * 1047 扫描小程序码、1048 长按图片识别小程序码、1049 手机相册选取小程序码。
 */
const SCAN_SCENES = [1011, 1012, 1013, 1047, 1048, 1049]

onLaunch((options) => {
  const scene = Number(options?.scene)
  if (!SCAN_SCENES.includes(scene)) {
    // 非扫码进入：不抢首页，停留在菜单主页
    return
  }
  void restoreActiveOrders()
})

async function restoreActiveOrders(): Promise<void> {
  try {
    await login()
    const active = await getActiveOrders()
    if (active.length) {
      // 等首页渲染完成再切 Tab，避免与启动流程竞争
      setTimeout(() => {
        uni.switchTab({
          url: '/pages/order/index',
          // 跳转失败（如页面栈尚未就绪）时静默忽略，不影响菜单主页使用
          fail: () => undefined
        })
      }, 300)
    }
  } catch {
    // 登录失败或网络异常不阻塞启动：公开接口（菜单 / 门店状态）仍可浏览，
    // 下单时 request 层会自动重试登录
  }
}
</script>

<style>
/* 全局基础样式（各页面 scoped 样式覆盖之） */
page {
  background-color: #f5f6f8;
  font-size: 28rpx;
  color: #303133;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.page-skeleton {
  padding: 24rpx;
}

.page-title {
  font-size: 36rpx;
  font-weight: 600;
  margin-bottom: 16rpx;
}

.page-tip {
  font-size: 24rpx;
  color: #909399;
  line-height: 1.6;
}

.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120rpx 40rpx;
  color: #909399;
}

.empty-icon {
  font-size: 96rpx;
  margin-bottom: 16rpx;
}

.empty-text {
  font-size: 28rpx;
  margin-bottom: 8rpx;
}

.empty-sub {
  font-size: 24rpx;
  color: #c0c4cc;
}

/* ============================================================================
   无障碍模式（T45，SRS 9.6 / LLD 11.4）——纯呈现层放大与高对比
   开启方式：页面根节点挂 .a11y-mode class（见 stores/a11y.ts）。
   纪律：这里只改「信息如何被看到」，不改「信息是什么」——不碰任何数据、状态机与接口。
   ============================================================================ */

/* 高对比底：纯白底 + 纯黑字，柜台强光下对比度最高；并抬高继承基准字号 */
.a11y-mode {
  min-height: 100vh;
  background-color: #ffffff;
  color: #000000;
  font-size: 32rpx;
}

/* 辅助文字加深：普通模式下的浅灰（#909399 / #c0c4cc）在强光下几乎不可见 */
.a11y-mode .a11y-dim {
  color: #333333 !important;
}

/* 放大档位：页面按语义给元素挂档，无需改动任何布局结构 */
.a11y-mode .a11y-sm {
  font-size: 30rpx !important;
}

.a11y-mode .a11y-md {
  font-size: 36rpx !important;
}

.a11y-mode .a11y-lg {
  font-size: 46rpx !important;
}

/* 取餐码：无障碍模式下进一步放大加粗——取餐场景的核心信息，必须一眼可辨 */
.a11y-mode .a11y-code {
  font-size: 180rpx !important;
  font-weight: 800 !important;
  letter-spacing: 8rpx;
}
</style>
