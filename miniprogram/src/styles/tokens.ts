/**
 * Design Token 的唯一出处（奶油暖调极简）。
 *
 * ## 为什么是 TS 常量 + CSS 自定义属性，而不是 SCSS 变量
 * 本项目**没有安装 sass**，页面样式全是原生 `<style>`（无 `lang="scss"`），
 * 因此 `uni.scss` 里的变量实际上从未被编译过——继续往那里写等于写了不生效。
 * 改用「CSS 自定义属性」作为运行时载体后：
 * - 页面与组件写 `color: var(--c-primary)`，不再出现硬编码 hex；
 * - 无障碍高对比模式只需在 `.a11y-mode` 上**重定义这几个变量**，
 *   整棵子树自动切换成高对比配色，不必逐条写 `!important` 覆盖；
 * - 需要拿颜色做逻辑的地方（tabBar 配置、canvas 绘制）从这里取常量，避免两处不一致。
 *
 * ## 同步约定
 * {@link CSS_VARIABLES} 与 `App.vue` 里 `page { … }` 的那一段必须保持一致：
 * `App.vue` 是 CSS 的落地点（`page` 选择器只能写在全局样式里），
 * 本文件是取值来源。改配色请**先改这里**，再同步 `App.vue`；
 * `scripts` 目录外的静态检查（`grep`）会检查旧色值不再残留。
 */

/** 主色与暖色系（焦糖暖棕）。 */
export const COLORS = {
  /** 主色：按钮、选中态、强调文字 */
  primary: '#C8845A',
  /** 主色亮档：渐变、悬浮态 */
  primaryLight: '#E8A87C',
  /** 主色暗档：按压态、正文强调字（在白底上对比度更足） */
  primaryDark: '#A9653F',

  /** 页面底色：米白奶油 */
  background: '#FFFBF5',
  /** 卡片面：纯白，与奶油底形成层级 */
  surface: '#FFFFFF',
  /** 暖色分区底：头部渐变、标签底 */
  backgroundWarm: '#FBF3E8',

  /** 主文字 */
  text: '#3D3229',
  /** 辅助文字 */
  textSub: '#8C8177',
  /** 反色文字 */
  textInverse: '#FFFFFF',

  /** 成功（已完成、可用） */
  success: '#7BA05B',
  /** 警示（待支付） */
  warning: '#D98E4A',
  /** 危险（已关闭、错误） */
  danger: '#C25B4E',
  /** 弱化底色（禁用块、骨架） */
  muted: '#F0E6DA',
  /** 描边 */
  border: '#EFE3D3'
} as const

/** 圆角。 */
export const RADIUS = {
  /** 卡片大圆角 */
  lg: '32rpx',
  /** 中等圆角（标签、输入框） */
  md: '20rpx',
  /** 胶囊 */
  pill: '999rpx'
} as const

/** 间距（页面边距与卡片间距统一，避免逐页拍脑袋）。 */
export const SPACING = {
  /** 页面左右边距 */
  page: '32rpx',
  /** 卡片之间 */
  card: '24rpx',
  /** 卡片内边距 */
  inner: '28rpx'
} as const

/** 字号与字重（对齐设计规范：标题 56 / 小标题 36 / 正文 28 rpx）。 */
export const TYPOGRAPHY = {
  fontFamily: "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  headingSize: '56rpx',
  headingWeight: 600,
  subheadingSize: '36rpx',
  subheadingWeight: 600,
  bodySize: '28rpx',
  bodyWeight: 400
} as const

/**
 * CSS 自定义属性映射（键为变量名，值为取值）。
 *
 * <p>与 `App.vue` 中 `page { … }` 的声明一一对应。之所以不在这里动态注入样式，
 * 是因为 `page` 选择器无法通过行内 style 绑定——它必须出现在全局样式表里。</p>
 */
export const CSS_VARIABLES: Record<string, string> = {
  '--c-primary': COLORS.primary,
  '--c-primary-light': COLORS.primaryLight,
  '--c-primary-dark': COLORS.primaryDark,
  '--c-bg': COLORS.background,
  '--c-surface': COLORS.surface,
  '--c-bg-warm': COLORS.backgroundWarm,
  '--c-text': COLORS.text,
  '--c-text-sub': COLORS.textSub,
  '--c-text-inverse': COLORS.textInverse,
  '--c-success': COLORS.success,
  '--c-warning': COLORS.warning,
  '--c-danger': COLORS.danger,
  '--c-muted': COLORS.muted,
  '--c-border': COLORS.border,
  '--r-lg': RADIUS.lg,
  '--r-md': RADIUS.md,
  '--r-pill': RADIUS.pill,
  '--s-page': SPACING.page,
  '--s-card': SPACING.card,
  '--s-inner': SPACING.inner,
  '--shadow-card': '0 6rpx 24rpx rgba(168, 122, 82, 0.08)',
  '--ease': 'cubic-bezier(0.4, 0, 0.2, 1)'
}
