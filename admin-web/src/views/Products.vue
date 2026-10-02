<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  fileUrl,
  getCategories,
  getProducts,
  getSpecGroups,
  updateCategoryProductStatus,
  updateProductStatus,
  updateProductStatusBatch,
  type AdminProduct,
  type Category,
  type SpecGroup
} from '@/api/product'
import AdminPageHeader from '@/components/AdminPageHeader.vue'
import ProductEditDrawer from '@/components/product/ProductEditDrawer.vue'

/**
 * 商品管理页（T21，LLD 3.5.1 / 7.2；T61 增补批量与分类估清）：
 * - 表格列：图（经 /api/files 代理 URL）/ 名称 / 分类 / 基础价 / 状态开关 / 排序 / 操作；
 * - 按分类（可选叠加状态）筛选，分页查询；
 * - 编辑抽屉：名称/描述/分类/基础价/排序/图片上传/适用规格组勾选（默认全选）；
 * - 上下架即时生效：下架后顾客端 GET /customer/menu 立即不含该商品（AC-08）；
 * - T61：勾选多行后可批量上下架；分类筛选态下提供「整类估清 / 整类恢复」。
 *
 * T61 的「估清」只是批量下架，不涉及任何库存数量——系统里没有 quantity 字段。
 */

const loading = ref(false)
const products = ref<AdminProduct[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const filterCategoryId = ref<number | null>(null)
const filterStatus = ref<number | null>(null)

const categories = ref<Category[]>([])
const specGroups = ref<SpecGroup[]>([])

const drawerVisible = ref(false)
const editingProduct = ref<AdminProduct | null>(null)

/** T61：列表多选。翻页与筛选变化后清空，避免把上一页的选择误算进本批。 */
const selectedIds = ref<number[]>([])
const batchBusy = ref(false)

const categoryNameMap = computed(() => {
  const map = new Map<number, string>()
  categories.value.forEach((category) => map.set(category.id, category.name))
  return map
})

async function loadProducts(): Promise<void> {
  loading.value = true
  try {
    const result = await getProducts({
      page: page.value,
      size: size.value,
      categoryId: filterCategoryId.value ?? undefined,
      status: (filterStatus.value ?? undefined) as 0 | 1 | undefined
    })
    products.value = result.list
    total.value = result.total
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
  }
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await getCategories()
  } catch {
    // 错误提示已由 request 层直显
  }
}

async function loadSpecGroups(): Promise<void> {
  try {
    specGroups.value = await getSpecGroups()
  } catch {
    // 错误提示已由 request 层直显
  }
}

function handleSearch(): void {
  page.value = 1
  selectedIds.value = []
  void loadProducts()
}

function resetFilter(): void {
  filterCategoryId.value = null
  filterStatus.value = null
  handleSearch()
}

function openCreate(): void {
  editingProduct.value = null
  drawerVisible.value = true
}

function openEdit(product: AdminProduct): void {
  editingProduct.value = product
  drawerVisible.value = true
}

/** 上下架开关：成功后刷新列表；失败同样刷新以回到服务端真值。 */
async function handleStatusChange(product: AdminProduct, value: number): Promise<void> {
  try {
    await updateProductStatus(product.id, value as 0 | 1)
    ElMessage.success(value === 1 ? '已上架' : '已下架，顾客端菜单已隐藏')
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    await loadProducts()
  }
}

function handleSaved(): void {
void loadProducts()
  // 商品增改可能带来分类商品数变化（分类页删除校验依赖该口径），顺带刷新分类
  void loadCategories()
}

/** T61：列表多选变更。只取 id，批量请求不需要整行数据。 */
function handleSelectionChange(rows: AdminProduct[]): void {
  selectedIds.value = rows.map((row) => row.id)
}

/** T61：批量上下架。**必须二次确认**——误点一次就把一批商品从顾客端菜单抹掉了，
 * 且没有撤销入口（要恢复只能重新上架）。
 *
 * 提示语区分「提交多少 / 实际生效多少」，让店长立刻发现半生效的情况。
 */
async function handleBatchStatus(status: 0 | 1): Promise<void> {
  if (selectedIds.value.length === 0 || batchBusy.value) {
    return
  }
  const verb = status === 0 ? '下架（估清）' : '上架'
  try {
    await ElMessageBox.confirm(
      `将对已选中的 ${selectedIds.value.length} 个商品执行「${verb}」。${
        status === 0 ? '执行后顾客端菜单立即不再展示这些商品。' : ''
      }`,
      `确认${verb}`,
      { type: 'warning', confirmButtonText: `确认${verb}`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  batchBusy.value = true
  try {
    const result = await updateProductStatusBatch(selectedIds.value, status)
    const suffix = result.affected === result.requested ? '' : '（部分商品原本就是该状态）'
    ElMessage.success(`已${verb} ${result.affected} / ${result.requested} 个商品${suffix}`)
    selectedIds.value = []
    await loadProducts()
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    batchBusy.value = false
  }
}

/**
 * T61：分类级一键估清 / 整类恢复。
 *
 * 只在已选定分类时可用——「整类」必须有明确的类，否则按钮语义不清。
 * 二次确认里点明该类商品数，让店长知道这一下会影响多少杯。
 */
async function handleCategoryStatus(status: 0 | 1): Promise<void> {
  const categoryId = filterCategoryId.value
  if (categoryId === null || batchBusy.value) {
    return
  }
  const category = categories.value.find((item) => item.id === categoryId)
  const verb = status === 0 ? '估清' : '恢复上架'
  try {
    await ElMessageBox.confirm(
      `确认对分类「${category?.name ?? categoryId}」执行「${verb}」？` +
        `该分类下共 ${category?.productCount ?? 0} 个商品，将全部置为${status === 0 ? '下架' : '上架'}。`,
      `确认${verb}`,
      { type: 'warning', confirmButtonText: `确认${verb}`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  batchBusy.value = true
  try {
    const result = await updateCategoryProductStatus(categoryId, status)
    const suffix = result.affected === result.total ? '' : `（${result.total - result.affected} 个原本就是该状态）`
    ElMessage.success(`「${result.categoryName}」已${verb} ${result.affected} / ${result.total} 个商品${suffix}`)
    selectedIds.value = []
    await loadProducts()
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    batchBusy.value = false
  }
}

onMounted(() => {
  void loadProducts()
  void loadCategories()
  void loadSpecGroups()
})
</script>

<template>
  <div class="products-page">
    <AdminPageHeader title="商品管理" subtitle="上下架即时同步顾客端菜单">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建商品</el-button>
    </AdminPageHeader>

    <main class="app-panel">
      <div class="filter-bar">
        <el-select
          v-model="filterCategoryId"
          placeholder="全部分类"
          clearable
          class="filter-select"
          @change="handleSearch"
        >
          <el-option
            v-for="category in categories"
            :key="category.id"
            :label="category.name"
            :value="category.id"
          />
        </el-select>
        <el-select v-model="filterStatus" placeholder="全部状态" clearable class="filter-select" @change="handleSearch">
          <el-option label="上架中" :value="1" />
          <el-option label="已下架" :value="0" />
        </el-select>
        <el-button @click="resetFilter">重置</el-button>
        <span class="filter-hint">下架后顾客端菜单立即隐藏（AC-08）</span>
      </div>

      <!-- T61：批量与分类级操作条。分类按钮只在选定分类时可用（「整类」必须有明确对象） -->
      <div class="batch-bar">
        <span class="batch-hint">
          已选 <strong>{{ selectedIds.length }}</strong> 项
        </span>
        <el-button
          size="small"
          :disabled="selectedIds.length === 0"
          :loading="batchBusy"
          @click="handleBatchStatus(0)"
        >
          批量下架（估清）
        </el-button>
        <el-button
          size="small"
          :disabled="selectedIds.length === 0"
          :loading="batchBusy"
          @click="handleBatchStatus(1)"
        >
          批量上架
        </el-button>
        <el-divider direction="vertical" />
        <span class="batch-hint">分类级：</span>
        <el-button
          size="small"
          type="warning"
          :disabled="filterCategoryId === null"
          :loading="batchBusy"
          @click="handleCategoryStatus(0)"
        >
          整类估清
        </el-button>
        <el-button
          size="small"
          :disabled="filterCategoryId === null"
          :loading="batchBusy"
          @click="handleCategoryStatus(1)"
        >
          整类恢复
        </el-button>
        <span v-if="filterCategoryId === null" class="batch-hint muted">先选定分类才能整类操作</span>
      </div>

      <el-table v-loading="loading" :data="products" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column label="图" width="88" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.imageKey"
              :src="fileUrl(row.imageKey)"
              fit="cover"
              lazy
              class="thumb"
            >
              <template #error>
                <div class="thumb-error">无图</div>
              </template>
              <template #placeholder>
                <div class="thumb-error">…</div>
              </template>
            </el-image>
            <div v-else class="thumb thumb-error">无图</div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="分类" width="120">
          <template #default="{ row }">
            {{ categoryNameMap.get(row.categoryId) ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="基础价" width="110" align="right">
          <template #default="{ row }">￥{{ row.basePrice }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.status"
              :active-value="1"
              :inactive-value="0"
              inline-prompt
              active-text="上架"
              inactive-text="下架"
              @change="(value: string | number | boolean) => handleStatusChange(row, Number(value))"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无商品" :image-size="72" class="app-empty" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          :current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="
            (value: number) => {
              page = value
              loadProducts()
            }
          "
        />
      </div>
    </main>

    <ProductEditDrawer
      v-model:visible="drawerVisible"
      :product="editingProduct"
      :categories="categories"
      :spec-groups="specGroups"
      :default-category-id="filterCategoryId"
      @saved="handleSaved"
    />
  </div>
</template>

<style scoped>
.products-page {
  display: flex;
  flex-direction: column;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  margin-bottom: var(--gap-4);
  flex-wrap: wrap;
}

.filter-select {
  width: 180px;
}

.filter-bar .filter-select + .filter-select {
  width: 140px;
}

.filter-hint {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

/* T61 批量操作条：与筛选条同宽，靠一条细边分隔 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  flex-wrap: wrap;
  padding: 8px 12px;
  margin-bottom: var(--gap-3);
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.batch-hint {
  font-size: var(--fs-xs);
  color: var(--text-2);
}

.batch-hint.muted {
  color: var(--text-3);
}

.thumb {
  width: 56px;
  height: 56px;
  border-radius: var(--radius-sm);
}

.thumb-error {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  font-size: var(--fs-xs);
  color: var(--text-3);
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--gap-4);
}
</style>
