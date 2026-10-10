<script setup lang="ts">
/**
 * 全站统一备案页脚（每页底部展示工信部 ICP 备案号与公安联网备案号，
 * 符合工信部备案及《计算机信息网络国际联网安全保护管理办法》悬挂要求）。
 * 小程序内无法直接跳转外部网页（beian.miit.gov.cn / beian.mps.gov.cn
 * 无法加入业务域名白名单），故展示备案号文案，点击复制对应备案查询网址，
 * 用户可在浏览器中打开核验。
 */
const QUERY_URLS = {
  icp: 'https://beian.miit.gov.cn/',
  // 公安部「互联网站安全服务备案平台」核验地址（code 为公安备案号数字部分）
  ga: 'https://beian.mps.gov.cn/#/query/webSearch?code=44098302441295'
} as const

function copyBeianUrl(url: string): void {
  uni.setClipboardData({
    data: url,
    success: () => {
      uni.showToast({ title: '备案查询网址已复制', icon: 'none' })
    }
  })
}
</script>

<template>
  <view class="icp-footer">
    <text class="icp-text" @click.stop="copyBeianUrl(QUERY_URLS.icp)">粤ICP备2026150217号</text>
    <text class="icp-divider">|</text>
    <view class="icp-ga" @click.stop="copyBeianUrl(QUERY_URLS.ga)">
      <image class="icp-ga-badge" src="/static/beian-ghs.png" mode="aspectFit" />
      <text class="icp-text">粤公网安备44098302441295号</text>
    </view>
  </view>
</template>

<style scoped>
.icp-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  padding: 32rpx 24rpx 8rpx;
}

.icp-text {
  font-size: 22rpx;
  line-height: 1.5;
  color: #909399;
  text-align: center;
}

.icp-divider {
  margin: 0 12rpx;
  font-size: 22rpx;
  color: #c0c4cc;
}

.icp-ga {
  display: flex;
  align-items: center;
  gap: 6rpx;
}

.icp-ga-badge {
  width: 24rpx;
  height: 24rpx;
  flex: none;
}
</style>
