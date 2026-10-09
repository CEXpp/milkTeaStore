<script setup lang="ts">
/**
 * 全站统一 ICP 备案页脚（每页底部展示备案号，符合工信部备案要求）。
 * 小程序内无法直接跳转外部网页（beian.miit.gov.cn 无法加入业务域名白名单），
 * 故展示备案号文案，点击复制工信部备案查询网址，用户可在浏览器中打开核验。
 */
const BEIAN_QUERY_URL = 'https://beian.miit.gov.cn/'

function copyBeianUrl(): void {
  uni.setClipboardData({
    data: BEIAN_QUERY_URL,
    success: () => {
      uni.showToast({ title: '备案查询网址已复制', icon: 'none' })
    }
  })
}
</script>

<template>
  <view class="icp-footer" @click="copyBeianUrl">
    <text class="icp-text">粤ICP备2026150217号</text>
  </view>
</template>

<style scoped>
.icp-footer {
  display: flex;
  justify-content: center;
  padding: 32rpx 24rpx 8rpx;
}

.icp-text {
  font-size: 22rpx;
  line-height: 1.5;
  color: #909399;
  text-align: center;
}
</style>
