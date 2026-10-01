<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  createCategory,
  deleteCategory,
  getCategories,
  updateCategory,
  type Category
} from '@/api/product'
import AdminPageHeader from '@/components/AdminPageHeader.vue'

/**
 * 分类管理页（T22，LLD 3.5.1）：
 * - 列表：名称 + 排序 + 商品数（productCount）；
 * - 新建 / 编辑：名称必填、名称唯一（后端 1001 冲突提示）、排序值控制菜单顺序；
 * - 删除前置校验：分类下有商品时后端返回 1001，前端同步禁用删除按钮并给出原因（避免误操作）。
 */

const loading = ref(false)
const categories = ref<Category[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', sortOrder: 0 })

const rules: FormRules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

async function load(): Promise<void> {
  loading.value = true
  try {
    categories.value = await getCategories()
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.sortOrder = categories.value.length ? Math.max(...categories.value.map((c) => c.sortOrder ?? 0)) + 1 : 1
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

function openEdit(category: Category): void {
  editingId.value = category.id
  form.name = category.name
  form.sortOrder = category.sortOrder ?? 0
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || saving.value) return
  saving.value = true
  try {
    const payload = { name: form.name.trim(), sortOrder: form.sortOrder }
    if (editingId.value === null) {
      await createCategory(payload)
      ElMessage.success('分类已创建')
    } else {
      await updateCategory(editingId.value, payload)
      ElMessage.success('分类已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示已由 request 层直显（如 1001 分类名称已存在）
  } finally {
    saving.value = false
  }
}

/** 删除：先二次确认；分类下有商品的情况前端已禁用按钮，后端仍会兜底校验（1001）。 */
async function handleDelete(category: Category): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除分类「${category.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCategory(category.id)
    ElMessage.success('分类已删除')
    await load()
  } catch {
    // 错误提示已由 request 层直显（如分类下有商品）
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="categories-page">
    <AdminPageHeader title="分类管理" subtitle="排序决定顾客端菜单分组顺序">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建分类</el-button>
    </AdminPageHeader>

    <main class="app-panel">
      <div class="tip">
        分类顺序决定顾客端菜单的分组顺序；分类下仍有商品时不可删除。
      </div>

      <el-table v-loading="loading" :data="categories">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="sortOrder" label="排序" width="100" align="center" />
        <el-table-column label="商品数" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.productCount > 0 ? 'info' : 'success'" effect="plain" size="small">
              {{ row.productCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-tooltip
              :disabled="row.productCount === 0"
              content="该分类下仍有商品，请先移动或删除商品"
              placement="top"
            >
              <span>
                <el-button link type="danger" :disabled="row.productCount > 0" @click="handleDelete(row)">
                  删除
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无分类" :image-size="72" class="app-empty" />
        </template>
      </el-table>
    </main>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建分类' : '编辑分类'"
      width="420px"
      align-center
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="32" placeholder="如：经典奶茶" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
          <span class="unit">数字越小越靠前</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.categories-page {
  display: flex;
  flex-direction: column;
}

.tip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: var(--gap-4);
  font-size: var(--fs-xs);
  color: var(--text-2);
  background: var(--bg-subtle);
  border-radius: var(--radius-md);
}

.unit {
  margin-left: var(--gap-2);
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
