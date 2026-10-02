<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import { askCopilot, type CopilotAnswer } from '@/api/copilot'
import AdminPageHeader from '@/components/AdminPageHeader.vue'

/**
 * 店长 Copilot 页（T67，W07）。
 *
 * 两段式呈现：**先给结论（人话），再给原始数据（可折叠）**。
 * 之所以两样都给，是因为店长需要能核对——AI 说的数字与报表不一致时一眼可见。
 * 表格里放的是工具返回原文，而工具直接委托统计接口，故数字必然同源。
 *
 * 本页没有任何写操作入口（AI 也没有该权限）；验证越权的示例提问在页面里给出来，
 * 方便演示时直接点。
 */

defineOptions({ name: 'Copilot' })

/** 一轮问答（含原始数据） */
interface Turn {
  question: string
  answer: CopilotAnswer | null
  failed: boolean
}

/** 演示用的示例提问：三个正常问题 + 一个越权问题（后者用于验证拦截） */
const SAMPLES: Array<{ label: string; question: string; probe?: boolean }> = [
  { label: '今天卖了多少钱？', question: '今天营业额多少？订单数和杯数分别是多少？' },
  { label: '哪种卖得最好？', question: '近 7 天哪种商品卖得最好？' },
  { label: '这几天在涨吗？', question: '最近 7 天的营业额趋势怎么样，是在涨还是在跌？' },
  { label: '（越权）帮我把珍珠奶茶下架', question: '帮我把珍珠奶茶下架', probe: true }
]

const question = ref('')
const turns = ref<Turn[]>([])
const loading = ref(false)
/** 会话标识：首轮由后端下发，后续回传以延续上下文 */
const conversationId = ref('')
const scrollAnchor = ref<HTMLElement | null>(null)

async function ask(text?: string): Promise<void> {
  const content = (text ?? question.value).trim()
  if (!content || loading.value) {
    return
  }
  loading.value = true
  const turn: Turn = { question: content, answer: null, failed: false }
  turns.value.push(turn)
  question.value = ''
  try {
    const answer = await askCopilot({ conversationId: conversationId.value || undefined, question: content })
    turn.answer = answer
    // 会话标识由服务端下发：前端自造一个 id 会与模型记忆窗口对不上，多轮追问就退化成单轮
    conversationId.value = answer.sessionId
  } catch {
    // 错误提示已由 request 层直显
    turn.failed = true
  } finally {
    loading.value = false
    await nextTick()
    scrollAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }
}

function clearAll(): void {
  turns.value = []
  conversationId.value = ''
}
</script>

<template>
  <section class="copilot-page">
    <AdminPageHeader title="经营参谋" subtitle="用自然语言问经营数据；回答只读，不能改任何数据">
      <el-button size="small" :disabled="!turns.length" @click="clearAll">清空对话</el-button>
    </AdminPageHeader>

    <!-- 能力边界前置说明：让店长一开始就知道 AI 不能改数据，而不是问完才被告知 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="AI 仅有查询权限"
      description="它可以读营业额、订单、排行与流水；不能下架商品、改价、暂停接单或修改订单。涉及修改的请求会被拒绝并指向对应页面。"
      class="copilot-alert"
    />

    <el-card shadow="never" class="chat-card">
      <div v-if="!turns.length" class="empty-area">
        <p class="empty-title">试试这些问题</p>
        <div class="samples">
          <el-button
            v-for="sample in SAMPLES"
            :key="sample.question"
            size="small"
            :type="sample.probe ? 'warning' : 'default'"
            plain
            @click="ask(sample.question)"
          >
            {{ sample.label }}
          </el-button>
        </div>
        <p class="empty-hint">最后一个是越权提问示例，用于验证 AI 会拒绝修改数据的请求</p>
      </div>

      <div v-else class="turns">
        <div v-for="(turn, index) in turns" :key="index" class="turn">
          <div class="bubble question">{{ turn.question }}</div>

          <div v-if="turn.failed" class="bubble answer failed">请求失败，请重试</div>

          <template v-else-if="turn.answer">
            <!-- 降级：模型不可用时不编结论，只提示 -->
            <div v-if="turn.answer.degraded" class="bubble answer degraded">
              {{ turn.answer.tip }}
            </div>
            <div v-else class="bubble answer">
              <p v-if="turn.answer.conclusion" class="conclusion">{{ turn.answer.conclusion }}</p>
              <!-- 越权拦截提示：警示色，避免店长误以为操作已生效 -->
              <el-alert
                v-if="turn.answer.tip"
                :type="turn.answer.rejected ? 'warning' : 'info'"
                :closable="false"
                show-icon
                :title="turn.answer.tip"
                class="turn-tip"
              />
            </div>

            <!-- 原始数据：默认折叠，展开可逐项核对（数字与账台统计页同源） -->
            <el-collapse v-if="turn.answer.tables.length" class="tables">
              <el-collapse-item
                v-for="(table, tableIndex) in turn.answer.tables"
                :key="tableIndex"
                :name="`${index}-${tableIndex}`"
              >
                <template #title>
                  <span class="table-title">原始数据 · {{ table.title }}</span>
                  <span v-if="table.args && table.args !== '{}'" class="table-args">{{ table.args }}</span>
                </template>
                <pre class="table-body">{{ table.data }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>

          <div v-else class="bubble answer pending">思考中…</div>
        </div>
        <div ref="scrollAnchor" />
      </div>

      <div class="ask-bar">
        <el-input
          v-model="question"
          placeholder="问问今天的生意，例如「昨天那个点为什么掉了」"
          :disabled="loading"
          @keyup.enter="ask()"
        />
        <el-button type="primary" :icon="Promotion" :loading="loading" @click="ask()">提问</el-button>
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.copilot-alert {
  margin-bottom: var(--gap-4);
}

.chat-card {
  display: flex;
  flex-direction: column;
}

.empty-area {
  padding: var(--gap-5) 0;
  text-align: center;
}

.empty-title {
  margin: 0 0 var(--gap-3);
  font-size: var(--fs-sm);
  color: var(--text-2);
}

.samples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-2);
  justify-content: center;
}

.empty-hint {
  margin: var(--gap-4) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.turns {
  display: flex;
  flex-direction: column;
  gap: var(--gap-4);
  max-height: 56vh;
  overflow-y: auto;
  padding-right: var(--gap-2);
}

.turn {
  display: flex;
  flex-direction: column;
  gap: var(--gap-2);
}

.bubble {
  padding: 10px 14px;
  font-size: var(--fs-sm);
  line-height: 1.7;
  border-radius: var(--radius-md);
}

.bubble.question {
  align-self: flex-end;
  max-width: 70%;
  color: #fff;
  background: var(--brand-500);
}

.bubble.answer {
  max-width: 88%;
  color: var(--text-1);
  background: var(--bg-subtle);
}

.bubble.answer.pending,
.bubble.answer.failed {
  color: var(--text-3);
}

.bubble.answer.degraded {
  color: #b88230;
  background: var(--c-warning-soft);
}

.conclusion {
  margin: 0;
  white-space: pre-wrap;
}

.turn-tip {
  margin-top: var(--gap-2);
}

.tables {
  max-width: 88%;
}

.table-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

.table-args {
  margin-left: 8px;
  font-size: var(--fs-xs);
  font-family: monospace;
  color: var(--text-3);
}

.table-body {
  margin: 0;
  font-family: monospace;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-2);
  white-space: pre-wrap;
}

.ask-bar {
  display: flex;
  gap: var(--gap-2);
  margin-top: var(--gap-4);
}
</style>
