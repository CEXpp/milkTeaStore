<script setup lang="ts">
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'
import { login } from '@/utils/request'

/**
 * 应用入口（T24）：启动时静默登录换取顾客 JWT（LLD 8.3）。
 * 菜单等公开接口不依赖 token；下单 / 订单类接口需要时也会自动补登录（request 层兜底）。
 */
onLaunch(() => {
  login().catch(() => {
    // 登录失败不阻塞启动：公开接口（菜单 / 门店状态）仍可浏览，下单时 request 层会重试登录
  })
})

onShow(() => {
  // 预留：切前台时刷新进行中订单（T27 订单页自身处理）
})

onHide(() => {
  // 预留：切后台时停止轮询（各页面自行处理，见 T27）
})
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
