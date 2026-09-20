<script setup lang="ts">
import { computed } from 'vue'
import type { SpecGroup } from '@/api/product'

/**
 * 适用规格组勾选（T21，LLD 7.2 商品编辑抽屉）：
 * - 仅「启用中」的规格组可选（停用组对顾客端不可见，勾选无意义）；
 * - 提供「全选 / 清空」快捷操作，新建商品默认全选（由父组件初始化为全部启用组）；
 * - 值即规格组 id 数组，提交时作为 specGroupIds 全量覆盖。
 */
const props = defineProps<{
  /** 全部规格组（GET /api/admin/spec-groups） */
  groups: SpecGroup[]
  /** 已选规格组 id 列表（v-model） */
  modelValue: number[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number[]): void
}>()

const selectableGroups = computed(() => props.groups.filter((group) => group.enabled === 1))

const allSelected = computed(
  () =>
    selectableGroups.value.length > 0 &&
    selectableGroups.value.every((group) => props.modelValue.includes(group.id))
)

function toggle(groupId: number): void {
  emit(
    'update:modelValue',
    props.modelValue.includes(groupId)
      ? props.modelValue.filter((id) => id !== groupId)
      : [...props.modelValue, groupId]
  )
}

function toggleAll(): void {
  emit('update:modelValue', allSelected.value ? [] : selectableGroups.value.map((group) => group.id))
}
</script>

<template>
  <div class="spec-group-check">
    <div class="toolbar">
      <span class="hint">
        已选 {{ modelValue.length }} / {{ selectableGroups.length }} 组
        <template v-if="!selectableGroups.length">（无启用中的规格组）</template>
      </span>
      <el-button link type="primary" size="small" @click="toggleAll">
        {{ allSelected ? '清空' : '全选' }}
      </el-button>
    </div>
    <el-checkbox
      v-for="group in selectableGroups"
      :key="group.id"
      class="spec-checkbox"
      :model-value="modelValue.includes(group.id)"
      @change="() => toggle(group.id)"
    >
      {{ group.name }}
      <span class="code">{{ group.multiSelect ? '多选' : '单选' }}</span>
    </el-checkbox>
    <div v-if="!modelValue.length" class="empty-tip">
      未勾选任何规格组时，顾客点单该商品将无需选择规格
    </div>
  </div>
</template>

<style scoped>
.spec-group-check {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.spec-checkbox {
  height: 28px;
}

.code {
  margin-left: 6px;
  font-size: 12px;
  color: #c0c4cc;
}

.empty-tip {
  font-size: 12px;
  color: #e6a23c;
}
</style>
