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
    // 登录失败或网络异常不阻塞启动：公开接口（菜单 / 门店状态）仍可正常浏览，
    // 下单时 request 层会自动重试登录
  }
}
</script>

<style>
/* ============================================================================
   Design Token（奶油暖调极简）——全站配色的唯一落地点
   ----------------------------------------------------------------------------
   为什么是 CSS 自定义属性而不是 SCSS 变量：本项目没有装 sass，页面样式全是原生 CSS，
   uni.scss 里的变量从未被编译过。改用自定义属性后，页面只写 var(--c-primary)，
   不再出现硬编码 hex；而无障碍高对比模式只需在 .a11y-mode 上重定义这几个变量，
   整棵子树自动切换配色（见下方）。取值来源见 src/styles/tokens.ts，改配色先改那里。
   ============================================================================ */
page {
  /* 主色：焦糖暖棕 */
  --c-primary: #c8845a;
  --c-primary-light: #e8a87c;
  --c-primary-dark: #a9653f;

  /* 底色：米白奶油底 + 纯白卡片 + 暖色分区 */
  --c-bg: #fffbf5;
  --c-surface: #ffffff;
  --c-bg-warm: #fbf3e8;

  /* 文字 */
  --c-text: #3d3229;
  --c-text-sub: #8c8177;
  --c-text-inverse: #ffffff;

  /* 语义色：已完成柔绿 / 待支付暖橙 / 关闭暖红 / 弱化底 */
  --c-success: #7ba05b;
  --c-warning: #d98e4a;
  --c-danger: #c25b4e;
  --c-muted: #f0e6da;
  --c-border: #efe3d3;

  /* 圆角：大圆角是这套视觉语言的核心特征 */
  --r-lg: 32rpx;
  --r-md: 20rpx;
  --r-pill: 999rpx;

  /* 间距：页面 32 / 卡片 24 / 卡片内 28，统一以免逐页拍脑袋 */
  --s-page: 32rpx;
  --s-card: 24rpx;
  --s-inner: 28rpx;

  --shadow-card: 0 6rpx 24rpx rgba(168, 122, 82, 0.08);
  --ease: cubic-bezier(0.4, 0, 0.2, 1);

  background-color: var(--c-bg);
  font-size: 28rpx;
  color: var(--c-text);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.page-skeleton {
  padding: var(--s-card);
}

.page-title {
  font-size: 36rpx;
  font-weight: 600;
  margin-bottom: 16rpx;
  color: var(--c-text);
}

.page-tip {
  font-size: 24rpx;
  color: var(--c-text-sub);
  line-height: 1.6;
}

.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120rpx 40rpx;
  color: var(--c-text-sub);
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
  color: var(--c-text-sub);
  opacity: 0.75;
}

/* ============================================================================
   通用交互：可点元素的按压反馈与卡片入场
   规范：120ms 缓动 + 轻微下移，不使用弹跳/旋转等喧哗动效
   ============================================================================ */
.tap {
  transition: transform 120ms var(--ease), opacity 120ms var(--ease);
}

.tap:active {
  transform: translateY(2rpx) scale(0.99);
  opacity: 0.88;
}

/* ============================================================================
   无障碍模式（T45，SRS 9.6 / LLD 11.4）——纯呈现层放大与高对比
   开启方式：页面根节点挂 .a11y-mode class（见 stores/a11y.ts）。
   纪律：这里只改「信息如何被看到」，不改「信息是什么」——不碰任何数据、状态机与接口。

   本轮 token 化的关键收益：高对比模式不再需要逐条 !important 覆盖，
   只要在这里重定义同样的变量名，整棵子树自动切换到高对比配色。
   ============================================================================ */

/* 高对比底：纯白底 + 纯黑字，柜台强光下对比度最高；并抬高继承基准字号 */
.a11y-mode {
  /* 强调色改用深档：焦糖暖棕在纯白底上对小字号偏浅，深档才够读 */
  --c-primary: #7a4526;
  --c-primary-light: #7a4526;
  --c-primary-dark: #5a3219;
  --c-bg: #ffffff;
  --c-surface: #ffffff;
  --c-bg-warm: #ffffff;
  --c-text: #000000;
  --c-text-sub: #333333;
  --c-border: #000000;
  --c-muted: #ffffff;
  /* 卡片阴影在强光下不可见，改成描边来区分层级（由 --c-border 承担） */
  --shadow-card: none;

  min-height: 100vh;
  background-color: var(--c-bg);
  color: var(--c-text);
  font-size: 32rpx;
}

/* 辅助文字加深：普通模式下的浅灰在强光下几乎不可见 */
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

/* 无障碍模式下禁用动效：微交互是锦上添花，晃动会对部分顾客造成干扰 */
.a11y-mode .tap:active {
  transform: none;
}
</style>
