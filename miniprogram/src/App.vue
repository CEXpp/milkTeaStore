<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { getActiveOrders } from '@/api/order'
import { login } from '@/utils/request'

/**
 * 应用入口：
 * - T24：启动时静默登录换取顾客 JWT（LLD 8.3）；菜单等公开接口不依赖 token；
 * - T27：再扫码进小程序时自动恢复进行中订单——存在进行中订单则定位到订单 Tab，
 *   顾客可立刻看到取餐码与制作进度（LLD 8.2「再扫码进小程序自动恢复进行中订单」）。
 */
onLaunch(() => {
  void restoreActiveOrders()
})

async function restoreActiveOrders(): Promise<void> {
  try {
    await login()
    const active = await getActiveOrders()
    if (active.length) {
      // 等首页渲染完成再切 Tab，避免与启动流程竞争
      setTimeout(() => {
        uni.switchTab({ url: '/pages/order/index' })
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
</style>
