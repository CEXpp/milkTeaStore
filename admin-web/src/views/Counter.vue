<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createCounterOrder, type CounterOrderResult } from '@/api/order'
import { getMenu, type MenuCategory, type MenuProduct } from '@/api/menu'
import { toCents } from '@/utils/money'
import type { DraftLine } from '@/components/counter/types'
import ProductPanel from '@/components/counter/ProductPanel.vue'
import SpecDialog from '@/components/counter/SpecDialog.vue'
import DraftPanel from '@/components/counter/DraftPanel.vue'

/**
 * 柜台点单 POS（T20，LLD 3.5 / 7.2）：
 * - 左栏分类 + 商品（菜单接口同源），点击开规格弹窗（单选组互斥 / 加料多选 / 实时合计）；
 * - 右栏草稿：增删行、数量步进、整单备注、合计；
 * - 「确认收款」→ POST /api/admin/counter-orders（创建即 PAID + 出取餐码）→ 大号取餐码窗口 5 秒；
 * - 收银键盘流：商品 → 回车加入草稿 → Ctrl+Enter 收款（Esc 关闭弹窗）。
 *
 * 布局：整屏两栏（meta.fullHeight），左右栏各自滚动，页面本身不滚动。
 */

/** 取餐码弹窗展示时长（秒） */
const PICKUP_DISPLAY_SECONDS = 5

const categories = ref<MenuCategory[]>([])
const paused = ref(false)
const loading = ref(false)
const specVisible = ref(false)
const activeProduct = ref<MenuProduct | null>(null)
const lines = ref<DraftLine[]>([])
const remark = ref('')
const submitting = ref(false)
const pickupInfo = ref<CounterOrderResult | null>(null)
const countdown = ref(PICKUP_DISPLAY_SECONDS)

let countdownTimer: number | null = null
let lineSeq = 0

async function loadMenu(): Promise<void> {
  loading.value = true
  try {
    const menu = await getMenu()
    categories.value = menu.categories
    paused.value = menu.paused
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
  }
}

function openSpec(product: MenuProduct): void {
  activeProduct.value = product
  specVisible.value = true
}

/** 规格弹窗确认 → 生成草稿行（本地展示价按「分」累加，实付以后端为准）。 */
function addLine(payload: { optionIds: number[]; quantity: number }): void {
  const product = activeProduct.value
  if (!product) return
  const optionNames: string[] = []
  let unitCents = toCents(product.basePrice)
  for (const group of product.specGroups) {
    for (const option of group.options) {
      if (payload.optionIds.includes(option.id)) {
        optionNames.push(option.name)
        unitCents += toCents(option.priceDelta)
      }
    }
  }
  lineSeq += 1
  lines.value.push({
    key: `line-${lineSeq}`,
    productId: product.id,
    productName: product.name,
    optionIds: payload.optionIds,
    optionNames,
    specText: optionNames.join('/'),
    unitCents,
    quantity: payload.quantity
  })
}

function changeQty(key: string, delta: number): void {
  const line = lines.value.find((item) => item.key === key)
  if (!line) return
  line.quantity = Math.min(20, Math.max(1, line.quantity + delta))
}

function removeLine(key: string): void {
  lines.value = lines.value.filter((item) => item.key !== key)
}

function clearDraft(): void {
  lines.value = []
  remark.value = ''
}

/** 确认收款：柜台单创建即 PAID（当面收款），出取餐码。 */
async function checkout(): Promise<void> {
  if (!lines.value.length) {
    ElMessage.warning('草稿为空，请先选择商品')
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const result = await createCounterOrder({
      items: lines.value.map((line) => ({
        productId: line.productId,
        optionIds: line.optionIds,
        quantity: line.quantity
      })),
      remark: remark.value.trim() || undefined
    })
    clearDraft()
    showPickup(result)
  } catch {
    // 失败提示已由 request 层直显（如 1002 商品下架）
  } finally {
    submitting.value = false
  }
}

/** 大号取餐码窗口：5 秒自动关闭，点击可立即关闭。 */
function showPickup(result: CounterOrderResult): void {
  pickupInfo.value = result
  countdown.value = PICKUP_DISPLAY_SECONDS
  stopCountdown()
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      closePickup()
    }
  }, 1000)
}

function closePickup(): void {
  pickupInfo.value = null
  stopCountdown()
}

function stopCountdown(): void {
  if (countdownTimer !== null) {
    window.clearInterval(countdownTimer)
    countdownTimer = null
  }
}

/** 收银键盘流：Ctrl+Enter（Mac 为 Cmd+Enter）直接收款。 */
function handleGlobalKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    void checkout()
  }
}

onMounted(() => {
  void loadMenu()
  window.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  stopCountdown()
})
</script>

<template>
  <div class="counter-page">
    <header class="counter-hintbar">
      <div class="hint-left">
        <el-tag v-if="paused" type="warning" effect="dark" size="small">暂停接单中（柜台点单不受影响）</el-tag>
        <span class="hint-text">点商品 → 回车加入草稿 → Ctrl+Enter 收款</span>
      </div>
      <span class="hint-right">共 {{ categories.length }} 个分类</span>
    </header>

    <main class="counter-body">
      <section class="left-pane">
        <ProductPanel :categories="categories" :loading="loading" @select="openSpec" />
      </section>
      <aside class="right-pane">
        <DraftPanel
          v-model:remark="remark"
          :lines="lines"
          :submitting="submitting"
          @change-qty="changeQty"
          @remove="removeLine"
          @clear="clearDraft"
          @checkout="checkout"
        />
      </aside>
    </main>

    <SpecDialog v-model:visible="specVisible" :product="activeProduct" @confirm="addLine" />

    <!-- 大号取餐码窗口（5 秒自动关闭，点击立即关闭） -->
    <div v-if="pickupInfo" class="pickup-overlay" @click="closePickup">
      <div class="pickup-box">
        <div class="pickup-label">取餐码</div>
        <div class="pickup-code">{{ pickupInfo.pickupCode }}</div>
        <div class="pickup-amount">￥{{ pickupInfo.totalAmount }}</div>
        <div class="pickup-progress">
          <span class="progress-bar" :style="{ width: `${(countdown / PICKUP_DISPLAY_SECONDS) * 100}%` }" />
        </div>
        <div class="pickup-tip">{{ countdown }} 秒后自动关闭（点击立即关闭）</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.counter-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: var(--gap-4);
  gap: var(--gap-3);
  box-sizing: border-box;
}

.counter-hintbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
  padding: 10px 16px;
  flex-shrink: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.hint-left {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  min-width: 0;
}

.hint-right {
  font-size: var(--fs-xs);
  color: var(--text-3);
  white-space: nowrap;
}

.counter-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: var(--gap-4);
  flex: 1;
  min-height: 0;
}

.left-pane,
.right-pane {
  padding: var(--gap-4);
  overflow-y: auto;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

@media (max-width: 1000px) {
  .counter-body {
    grid-template-columns: minmax(0, 1fr);
  }
}

.pickup-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(16, 24, 40, 0.55);
  backdrop-filter: blur(3px);
  animation: overlay-in var(--dur-base) var(--ease-out);
}

@keyframes overlay-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.pickup-box {
  min-width: 380px;
  padding: 36px 56px 28px;
  text-align: center;
  background: var(--bg-surface);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-float);
  animation: pickup-in var(--dur-base) var(--ease-out);
}

@keyframes pickup-in {
  from {
    opacity: 0;
    transform: translateY(12px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.pickup-label {
  font-size: var(--fs-body);
  color: var(--text-3);
  letter-spacing: 4px;
}

.pickup-code {
  margin: 12px 0;
  font-size: 120px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--brand-500);
  font-family: 'Consolas', 'Menlo', monospace;
  text-shadow: 0 6px 24px rgba(75, 91, 214, 0.18);
}

.pickup-amount {
  font-size: 24px;
  font-weight: 600;
  color: var(--c-danger);
}

.pickup-progress {
  height: 4px;
  margin: 18px auto 0;
  overflow: hidden;
  background: var(--bg-subtle);
  border-radius: var(--radius-pill);
}

.progress-bar {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--brand-400) 0%, var(--brand-600) 100%);
  transition: width 1s linear;
}

.pickup-tip {
  margin-top: 12px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
