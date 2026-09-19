<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createSpecOption,
  getSpecGroups,
  updateSpecGroup,
  updateSpecOption,
  type SpecGroup,
  type SpecOption
} from '@/api/product'
import AdminPageHeader from '@/components/AdminPageHeader.vue'

/**
 * 规格模板管理页（T22，LLD 3.5.2）：
 * - 四组系统模板（杯型/温度/甜度/加料）不提供删除，只做组级「名称/排序/启用」维护；
 * - 选项级：新增、改名、改价差、排序、启停（停用选项立即从顾客端可选集消失——MenuService 只取 enabled=1）；
 * - 选项不做物理删除：历史订单已存规格快照，停用即可达成「不再可选」的业务目的（LLD 3.5.2 口径）。
 */

const loading = ref(false)
const groups = ref<SpecGroup[]>([])

const optionDialogVisible = ref(false)
const optionSaving = ref(false)
const optionFormRef = ref<FormInstance>()
const editingOptionId = ref<number | null>(null)
const editingGroupId = ref<number | null>(null)
const optionForm = reactive({ name: '', priceDelta: 0, sortOrder: 0, enabled: 1 })

const groupDialogVisible = ref(false)
const groupSaving = ref(false)
const groupFormRef = ref<FormInstance>()
const editingGroup = ref<SpecGroup | null>(null)
const groupForm = reactive({ name: '', sortOrder: 0, enabled: 1 })

const optionRules: FormRules = {
  name: [{ required: true, message: '请输入规格项名称', trigger: 'blur' }]
}
const groupRules: FormRules = {
  name: [{ required: true, message: '请输入规格组名称', trigger: 'blur' }]
}

async function load(): Promise<void> {
  loading.value = true
  try {
    groups.value = await getSpecGroups()
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
  }
}

function openCreateOption(group: SpecGroup): void {
  editingGroupId.value = group.id
  editingOptionId.value = null
  optionForm.name = ''
  optionForm.priceDelta = 0
  optionForm.sortOrder = group.options.length
    ? Math.max(...group.options.map((option) => option.sortOrder ?? 0)) + 1
    : 1
  optionForm.enabled = 1
  optionFormRef.value?.clearValidate()
  optionDialogVisible.value = true
}

function openEditOption(group: SpecGroup, option: SpecOption): void {
  editingGroupId.value = group.id
  editingOptionId.value = option.id
  optionForm.name = option.name
  optionForm.priceDelta = Number(option.priceDelta)
  optionForm.sortOrder = option.sortOrder ?? 0
  optionForm.enabled = option.enabled
  optionFormRef.value?.clearValidate()
  optionDialogVisible.value = true
}

async function submitOption(): Promise<void> {
  const valid = await optionFormRef.value?.validate().catch(() => false)
  if (!valid || optionSaving.value) return
  optionSaving.value = true
  try {
    const payload = {
      name: optionForm.name.trim(),
      priceDelta: optionForm.priceDelta.toFixed(2),
      sortOrder: optionForm.sortOrder,
      enabled: optionForm.enabled
    }
    if (editingOptionId.value === null) {
      await createSpecOption(editingGroupId.value as number, payload)
      ElMessage.success('规格项已新增')
    } else {
      await updateSpecOption(editingOptionId.value, payload)
      ElMessage.success('规格项已更新')
    }
    optionDialogVisible.value = false
    await load()
  } catch {
    // 错误提示已由 request 层直显（如 1001 同组重名）
  } finally {
    optionSaving.value = false
  }
}

/** 选项启停：停用后顾客端可选集立即不含该项。 */
async function toggleOption(option: SpecOption): Promise<void> {
  try {
    await updateSpecOption(option.id, {
      name: option.name,
      priceDelta: String(option.priceDelta),
      sortOrder: option.sortOrder,
      enabled: option.enabled
    })
    ElMessage.success(option.enabled === 1 ? '已启用，顾客端可再次选择' : '已停用，顾客端不再可选')
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    await load()
  }
}

function openGroupEdit(group: SpecGroup): void {
  editingGroup.value = group
  groupForm.name = group.name
  groupForm.sortOrder = group.sortOrder ?? 0
  groupForm.enabled = group.enabled
  groupFormRef.value?.clearValidate()
  groupDialogVisible.value = true
}

async function submitGroup(): Promise<void> {
  const valid = await groupFormRef.value?.validate().catch(() => false)
  if (!valid || groupSaving.value || !editingGroup.value) return
  groupSaving.value = true
  try {
    await updateSpecGroup(editingGroup.value.id, {
      name: groupForm.name.trim(),
      sortOrder: groupForm.sortOrder,
      enabled: groupForm.enabled
    })
    ElMessage.success('规格组已更新')
    groupDialogVisible.value = false
    await load()
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    groupSaving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="specs-page">
    <AdminPageHeader title="规格模板" />
    <div class="page-body" v-loading="loading">
      <div class="tip">
        四组系统模板不可删除；停用选项或整组后，顾客端菜单立即不再返回相应可选集。
      </div>

      <el-card v-for="group in groups" :key="group.id" class="group-card" shadow="never">
        <template #header>
          <div class="group-header">
            <div class="group-title">
              <span class="group-name">{{ group.name }}</span>
              <el-tag size="small" :type="group.multiSelect ? 'warning' : 'info'" effect="plain">
                {{ group.multiSelect ? '多选' : '单选' }}
              </el-tag>
              <span class="group-code">{{ group.code }}</span>
              <el-tag v-if="group.enabled === 0" size="small" type="danger" effect="dark">已停用</el-tag>
            </div>
            <div class="group-actions">
              <span class="sort-hint">排序 {{ group.sortOrder }}</span>
              <el-button link type="primary" @click="openGroupEdit(group)">编辑组</el-button>
              <el-button type="primary" plain size="small" @click="openCreateOption(group)">新增选项</el-button>
            </div>
          </div>
        </template>

        <el-table :data="group.options" size="small" border>
          <el-table-column prop="name" label="选项名称" min-width="140" />
          <el-table-column label="价差" width="110" align="right">
            <template #default="{ row }">
              {{ row.priceDelta === '0.00' ? '—' : `￥${row.priceDelta}` }}
            </template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
          <el-table-column label="启用" width="110" align="center">
            <template #default="{ row }">
              <el-switch
                v-model="row.enabled"
                :active-value="1"
                :inactive-value="0"
                inline-prompt
                active-text="启用"
                inactive-text="停用"
                @change="() => toggleOption(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditOption(group, row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="该组暂无规格项" :image-size="60" />
          </template>
        </el-table>
      </el-card>

      <el-empty v-if="!loading && !groups.length" description="暂无规格组" />
    </div>

    <el-dialog
      v-model="optionDialogVisible"
      :title="editingOptionId === null ? '新增规格项' : '编辑规格项'"
      width="440px"
    >
      <el-form ref="optionFormRef" :model="optionForm" :rules="optionRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="optionForm.name" maxlength="32" placeholder="如：大杯 / 珍珠" />
        </el-form-item>
        <el-form-item label="价差">
          <el-input-number v-model="optionForm.priceDelta" :min="-100" :max="100" :precision="2" :step="1" />
          <span class="unit">元（在商品基础价上累加）</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="optionForm.sortOrder" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="optionForm.enabled" :active-value="1" :inactive-value="0" />
          <span class="unit">停用后顾客端不可选</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="optionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="optionSaving" @click="submitOption">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="groupDialogVisible" title="编辑规格组" width="440px">
      <el-form ref="groupFormRef" :model="groupForm" :rules="groupRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="groupForm.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="groupForm.sortOrder" :min="0" :max="9999" />
          <span class="unit">决定菜单中规格组的展示顺序</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="groupForm.enabled" :active-value="1" :inactive-value="0" />
          <span class="unit">停用后整组不出现在顾客端菜单</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="groupSaving" @click="submitGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.specs-page {
  min-height: 100%;
  padding: 16px;
  box-sizing: border-box;
}

.page-body {
  padding: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.tip {
  margin-bottom: 12px;
  font-size: 12px;
  color: #909399;
}

.group-card {
  margin-bottom: 14px;
  border: 1px solid #ebeef5;
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.group-name {
  font-size: 15px;
  font-weight: 600;
}

.group-code {
  font-size: 12px;
  color: #c0c4cc;
}

.group-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sort-hint {
  font-size: 12px;
  color: #909399;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
