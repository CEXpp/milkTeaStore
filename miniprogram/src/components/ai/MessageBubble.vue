<script setup lang="ts">
/**
 * 聊天气泡（T33）：用户消息靠右（无头像），AI 消息靠左（带「茶」头像）。
 *
 * 只负责渲染一条文本消息；CARD 形态的草稿卡片由页面在气泡下方追加
 * {@link ./DraftCard.vue} 渲染——文本与卡片是两种独立的展示单元。
 */
defineProps<{
  role: 'user' | 'assistant'
  text: string
}>()
</script>

<template>
  <view class="bubble-row" :class="role">
    <view v-if="role === 'assistant'" class="avatar">茶</view>
    <view class="bubble">{{ text }}</view>
  </view>
</template>

<style scoped>
.bubble-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 20rpx;
}

.bubble-row.user {
  justify-content: flex-end;
}

.avatar {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  margin-right: 16rpx;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 26rpx;
  line-height: 64rpx;
  text-align: center;
}

.bubble {
  max-width: 74%;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  font-size: 30rpx;
  line-height: 1.5;
  word-break: break-word;
}

.bubble-row.assistant .bubble {
  background: #fff;
  color: #333;
}

.bubble-row.user .bubble {
  background: #409eff;
  color: #fff;
}
</style>
