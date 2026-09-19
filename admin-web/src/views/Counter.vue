<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
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
 */

/** 取餐码弹窗展示时长（秒） */
const PICKUP_DISPLAY_SECONDS = 5

const router = useRouter()

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
    <header class="counter-topbar">
      <div class="topbar-left">
        <el-button link type="primary" @click="router.push('/board')">← 返回看板</el-button>
        <span class="counter-title">柜台点单</span>
        <el-tag v-if="paused" type="warning" effect="dark">暂停接单中（柜台点单不受影响）</el-tag>
      </div>
      <div class="topbar-hint">键盘流：点商品 → 回车加入草稿 → Ctrl+Enter 收款</div>
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

    <!-- 大号取餐码窗口（5 秒自动关闭） -->
    <div v-if="pickupInfo" class="pickup-overlay" @click="closePickup">
      <div class="pickup-box">
        <div class="pickup-label">取餐码</div>
        <div class="pickup-code">{{ pickupInfo.pickupCode }}</div>
        <div class="pickup-amount">￥{{ pickupInfo.totalAmount }}</div>
        <div class="pickup-tip">{{ countdown }} 秒后自动关闭（点击立即关闭）</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.counter-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  box-sizing: border-box;
  padding: 12px 16px;
}

.counter-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 16px;
  margin-bottom: 12px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.counter-title {
  font-size: 18px;
  font-weight: 600;
}

.topbar-hint {
  font-size: 12px;
  color: #909399;
}

.counter-body {
  display: grid;
  grid-template-columns: 1fr 380px;
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.left-pane,
.right-pane {
  padding: 14px;
  overflow-y: auto;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

@media (max-width: 1000px) {
  .counter-body {
    grid-template-columns: 1fr;
  }
}

.pickup-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.55);
}

.pickup-box {
  min-width: 360px;
  padding: 40px 56px;
  text-align: center;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.3);
}

.pickup-label {
  font-size: 16px;
  color: #909399;
  letter-spacing: 4px;
}

.pickup-code {
  margin: 12px 0;
  font-size: 120px;
  font-weight: 700;
  line-height: 1.1;
  color: #409eff;
  font-family: 'Consolas', 'Menlo', monospace;
}

.pickup-amount {
  font-size: 24px;
  font-weight: 600;
  color: #f56c6c;
}

.pickup-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
