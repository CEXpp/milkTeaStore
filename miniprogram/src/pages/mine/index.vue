<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getProfile, getPreference, updateNickname, uploadAvatar, type PreferenceView } from '@/api/user'
import { getShopStatus } from '@/api/menu'
import { BASE_URL } from '@/utils/config'
import { useA11yStore } from '@/stores/a11y'
import { useSpeechStore } from '@/stores/speech'
import IcpFooter from '@/components/IcpFooter.vue'

/**
 * 个人中心（本轮新增）。
 *
 * <p><b>登录是无阻塞的</b>：顾客不填昵称、不选头像也能下单——资料是「可选补充」，
 * 所以这里任何一项保存失败都不影响点单主链路，页面也不设强制授权弹窗。</p>
 *
 * <p><b>口味档案由后端隐式聚合</b>（只从已成交订单学习，不解析对话内容）。
 * 因此新客看到的是空态引导而不是空标签：空标签会让人以为坏了。</p>
 */

const a11y = useA11yStore()
const speech = useSpeechStore()

const nickname = ref('')
/** 已保存的昵称：用于判断「有没有改动」，避免每次点保存都发一次无意义的请求 */
const savedNickname = ref('')
const avatarUrl = ref('')
const preference = ref<PreferenceView | null>(null)
const shopStatus = ref('')
const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)

/** 头像绝对地址：后端只给相对代理路径（与商品图同一约定） */
const avatarSrc = computed(() => (avatarUrl.value ? BASE_URL + avatarUrl.value : ''))

/** 昵称是否可保存：非空、未超长、且与已保存值不同 */
const canSaveNickname = computed(() => {
  const value = nickname.value.trim()
  return !saving.value && value.length > 0 && value.length <= 20 && value !== savedNickname.value
})

const preferenceTitle = computed(() => {
  if (!preference.value?.available) {
    return '还没有你的口味画像'
  }
  return `基于最近 ${preference.value.sampleOrders} 单`
})

onShow(async () => {
  speech.init()
  await Promise.all([loadProfile(), loadPreference(), loadShopStatus()])
  loading.value = false
})

async function loadProfile(): Promise<void> {
  try {
    const profile = await getProfile()
    nickname.value = profile.nickname || ''
    savedNickname.value = profile.nickname || ''
    avatarUrl.value = profile.avatarUrl || ''
  } catch (error) {
    // 未登录或网络异常都不该让页面卡在加载态：令牌过期时 request 层会静默重登一次
    console.error('读取资料失败', error)
  }
}

async function loadPreference(): Promise<void> {
  try {
    preference.value = await getPreference()
  } catch (error) {
    console.error('读取口味档案失败', error)
  }
}

async function loadShopStatus(): Promise<void> {
  try {
    const status = await getShopStatus()
    shopStatus.value = status.paused ? '暂停接单中' : '营业中'
  } catch {
    shopStatus.value = ''
  }
}

/** 微信头像选择：拿到的是本机临时路径，必须立刻上传转存，不能直接存这个路径 */
async function onChooseAvatar(event: { detail?: { avatarUrl?: string } }): Promise<void> {
  const tempPath = event?.detail?.avatarUrl
  if (!tempPath || uploading.value) {
    return
  }
  uploading.value = true
  uni.showLoading({ title: '上传中', mask: true })
  try {
    const profile = await uploadAvatar(tempPath)
    avatarUrl.value = profile.avatarUrl || ''
    uni.showToast({ title: '头像已更新', icon: 'none' })
  } catch (error) {
    console.error('头像上传失败', error)
    uni.showToast({ title: error instanceof Error ? error.message : '头像上传失败', icon: 'none' })
  } finally {
    uploading.value = false
    uni.hideLoading()
  }
}

async function saveNickname(): Promise<void> {
  if (!canSaveNickname.value) {
    return
  }
  saving.value = true
  try {
    const profile = await updateNickname(nickname.value.trim())
    nickname.value = profile.nickname || ''
    savedNickname.value = profile.nickname || ''
    uni.showToast({ title: '昵称已保存', icon: 'none' })
  } catch (error) {
    console.error('保存昵称失败', error)
    uni.showToast({ title: error instanceof Error ? error.message : '保存失败', icon: 'none' })
  } finally {
    saving.value = false
  }
}

function goOrders(): void {
  uni.switchTab({ url: '/pages/order/index', fail: () => undefined })
}

function goGroup(): void {
  uni.navigateTo({ url: '/pages/group/index', fail: () => undefined })
}

/**
 * 开关事件统一在这里解包。
 *
 * <p>不在模板里写 `($event.detail as {value:boolean}).value`：模板表达式对 TS 语法支持有限，
 * `vue-tsc` 会直接报语法错。写成方法既类型安全，也让模板保持「只做绑定」。</p>
 */
function onA11yChange(event: Event): void {
  a11y.setEnabled(switchValueOf(event))
}

function onSpeechChange(event: Event): void {
  speech.setEnabled(switchValueOf(event))
}

/** 从 switch 的 change 事件里取开关值（小程序把它放在 `detail.value`）。 */
function switchValueOf(event: Event): boolean {
  const detail = (event as unknown as { detail?: { value?: boolean } }).detail
  return Boolean(detail?.value)
}
</script>

<template>
  <view class="mine-page" :class="{ 'a11y-mode': a11y.enabled }">
    <!-- 头部身份区：柔和暖色渐变 + 可点选的头像 -->
    <view class="hero">
      <button class="avatar-btn tap" open-type="chooseAvatar" @chooseavatar="onChooseAvatar">
        <image v-if="avatarSrc" class="avatar" :src="avatarSrc" mode="aspectFill" />
        <view v-else class="avatar avatar-placeholder">
          <text class="avatar-placeholder-text">头像</text>
        </view>
        <view class="avatar-badge">
          <text class="avatar-badge-text">{{ uploading ? '上传中' : '更换' }}</text>
        </view>
      </button>

      <view class="identity">
        <input
          v-model="nickname"
          class="nick-input a11y-md"
          type="nickname"
          maxlength="20"
          placeholder="点击填写昵称"
          placeholder-class="nick-placeholder"
        />
        <text class="identity-tip a11y-dim">昵称与头像可不填，不影响点单</text>
      </view>

      <button
        class="nick-save tap"
        :class="{ 'nick-save-disabled': !canSaveNickname }"
        :disabled="!canSaveNickname"
        @click="saveNickname"
      >
        {{ saving ? '保存中' : '保存' }}
      </button>
    </view>

    <view v-if="loading" class="page-tip section">资料加载中…</view>

    <!-- 口味档案：后端隐式聚合，只从已成交订单学习 -->
    <view class="card">
      <view class="card-head">
        <text class="card-title a11y-md">口味档案</text>
        <text class="card-note a11y-dim">{{ preferenceTitle }}</text>
      </view>

      <template v-if="preference?.available">
        <view v-if="preference.favoriteProductName" class="tag-row">
          <text class="tag-label a11y-dim">常点</text>
          <text class="tag tag-primary a11y-sm">{{ preference.favoriteProductName }}</text>
        </view>
        <view v-for="group in preference.groups" :key="group.code" class="tag-row">
          <text class="tag-label a11y-dim">{{ group.name }}</text>
          <text v-for="option in group.optionNames" :key="option" class="tag a11y-sm">{{ option }}</text>
        </view>
        <text class="card-note a11y-dim">点单时只作为默认值，你说的以你为准</text>
      </template>

      <view v-else class="pref-empty">
        <view class="pref-empty-icon">杯</view>
        <text class="pref-empty-text a11y-sm">下过几单之后，这里会记住你常喝的甜度与加料</text>
        <text class="card-note a11y-dim">只根据已完成订单归纳，不会读取聊天内容</text>
      </view>
    </view>

    <!-- 常用入口 -->
    <view class="card">
      <view class="row tap" @click="goOrders">
        <text class="row-text a11y-md">我的订单</text>
        <text class="row-arrow">›</text>
      </view>
      <view class="row tap" @click="goGroup">
        <text class="row-text a11y-md">发起拼单</text>
        <text class="row-arrow">›</text>
      </view>
      <view v-if="shopStatus" class="row row-static">
        <text class="row-text a11y-md">门店状态</text>
        <text class="row-value a11y-dim">{{ shopStatus }}</text>
      </view>
    </view>

    <!-- 设置：两个开关都是纯呈现层偏好，服务端不感知 -->
    <view class="card">
      <view class="switch-row">
        <view class="switch-info">
          <text class="row-text a11y-md">无障碍模式</text>
          <text class="card-note a11y-dim">放大字号并提高对比度，柜台强光下更易读</text>
        </view>
        <switch :checked="a11y.enabled" color="#C8845A" @change="onA11yChange" />
      </view>
      <view v-if="speech.available" class="switch-row">
        <view class="switch-info">
          <text class="row-text a11y-md">语音播报</text>
          <text class="card-note a11y-dim">AI 配好单后把内容念一遍</text>
        </view>
        <switch :checked="speech.enabled" color="#C8845A" @change="onSpeechChange" />
      </view>
    </view>

    <IcpFooter />
  </view>
</template>

<style scoped>
.mine-page {
  min-height: 100vh;
  padding-bottom: 40rpx;
}

/* 头部：暖色渐变而非纯色，避免大块白底显得空 */
.hero {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 40rpx var(--s-page) 48rpx;
  background: linear-gradient(160deg, var(--c-bg-warm) 0%, var(--c-bg) 100%);
}

/* 头像按钮：清掉小程序 button 的默认外观（边框用 ::after 画的，必须单独去掉） */
.avatar-btn {
  position: relative;
  width: 128rpx;
  height: 128rpx;
  padding: 0;
  margin: 0;
  border: none;
  line-height: normal;
  background: transparent;
  flex: none;
}

.avatar-btn::after {
  border: none;
}

.avatar {
  width: 128rpx;
  height: 128rpx;
  border-radius: var(--r-pill);
  background: var(--c-muted);
}

.avatar-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed var(--c-border);
}

.avatar-placeholder-text {
  font-size: 24rpx;
  color: var(--c-text-sub);
}

.avatar-badge {
  position: absolute;
  right: -8rpx;
  bottom: -4rpx;
  padding: 2rpx 12rpx;
  border-radius: var(--r-pill);
  background: var(--c-primary);
}

.avatar-badge-text {
  font-size: 20rpx;
  color: var(--c-text-inverse);
}

.identity {
  flex: 1;
  min-width: 0;
}

.nick-input {
  width: 100%;
  height: 64rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: var(--c-text);
  background: transparent;
  border: none;
  outline: none;
}

.nick-placeholder {
  color: var(--c-text-sub);
  font-weight: 400;
}

.identity-tip {
  font-size: 22rpx;
}

.nick-save {
  flex: none;
  padding: 0 28rpx;
  height: 60rpx;
  line-height: 60rpx;
  border-radius: var(--r-pill);
  border: 2rpx solid var(--c-primary);
  background: transparent;
  color: var(--c-primary);
  font-size: 26rpx;
}

.nick-save::after {
  border: none;
}

.nick-save-disabled {
  border-color: var(--c-border);
  color: var(--c-text-sub);
}

/* 卡片：大圆角 + 极轻阴影，靠留白分区 */
.card {
  margin: 0 var(--s-page) var(--s-card);
  padding: var(--s-inner);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--shadow-card);
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 20rpx;
}

.card-title {
  font-size: 32rpx;
  font-weight: 600;
  color: var(--c-text);
}

.card-note {
  font-size: 22rpx;
  line-height: 1.5;
}

.tag-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 16rpx;
}

.tag-label {
  font-size: 24rpx;
  width: 88rpx;
  flex: none;
}

.tag {
  padding: 6rpx 20rpx;
  border-radius: var(--r-pill);
  background: var(--c-bg-warm);
  color: var(--c-primary-dark);
  font-size: 24rpx;
}

.tag-primary {
  background: var(--c-primary);
  color: var(--c-text-inverse);
}

.pref-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32rpx 0 8rpx;
  gap: 12rpx;
}

.pref-empty-icon {
  width: 88rpx;
  height: 88rpx;
  border-radius: var(--r-pill);
  background: var(--c-bg-warm);
  color: var(--c-primary);
  font-size: 36rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.pref-empty-text {
  font-size: 26rpx;
  color: var(--c-text);
  text-align: center;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 88rpx;
}

.row + .row {
  border-top: 2rpx solid var(--c-border);
}

.row-static {
  opacity: 0.9;
}

.row-text {
  font-size: 30rpx;
  color: var(--c-text);
}

.row-value,
.row-arrow {
  font-size: 26rpx;
  color: var(--c-text-sub);
}

.row-arrow {
  font-size: 40rpx;
  line-height: 1;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  min-height: 96rpx;
}

.switch-row + .switch-row {
  border-top: 2rpx solid var(--c-border);
}

.switch-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.section {
  margin: 0 var(--s-page) var(--s-card);
}
</style>
