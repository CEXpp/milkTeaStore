<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import {
  createProduct,
  fileUrl,
  updateProduct,
  uploadImage,
  type AdminProduct,
  type Category,
  type ProductSaveRequest,
  type SpecGroup
} from '@/api/product'
import SpecGroupCheck from './SpecGroupCheck.vue'

/**
 * 商品新建 / 编辑抽屉（T21，LLD 7.2 / 7.3）：
 * - 字段：名称 / 分类 / 基础价 / 排序 / 描述 / 图片 / 适用规格组勾选（默认全选）；
 * - 图片走 el-upload 直传 POST /api/admin/upload，表单只保存后端返回的 key（LLD 9.4.2）；
 * - 保存：新建走 POST，编辑走 PUT（价格改动不影响历史订单——后端快照机制）。
 */
const props = defineProps<{
  visible: boolean
  /** null = 新建 */
  product: AdminProduct | null
  categories: Category[]
  specGroups: SpecGroup[]
  /** 新建时的默认分类（取自列表当前筛选） */
  defaultCategoryId?: number | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'saved'): void
}>()

/** 图片上传约束（与后端 MinioFileStorageService 白名单一致） */
const ACCEPT_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_IMAGE_SIZE = 2 * 1024 * 1024

const formRef = ref<FormInstance>()
const saving = ref(false)
const uploading = ref(false)

const form = reactive<{
  name: string
  categoryId: number | null
  basePrice: number
  sortOrder: number
  description: string
  imageKey: string
  specGroupIds: number[]
}>({
  name: '',
  categoryId: null,
  basePrice: 0,
  sortOrder: 0,
  description: '',
  imageKey: '',
  specGroupIds: []
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  basePrice: [{ required: true, message: '请输入基础价', trigger: 'blur' }]
}

const isEdit = computed(() => Boolean(props.product))
const title = computed(() => (isEdit.value ? `编辑商品 · ${props.product?.name ?? ''}` : '新建商品'))
const previewUrl = computed(() => fileUrl(form.imageKey))

/** 打开抽屉时初始化表单：编辑取原值，新建置默认值（规格组默认全选）。 */
watch(
  () => props.visible,
  (opened) => {
    if (!opened) return
    formRef.value?.clearValidate()
    const product = props.product
    if (product) {
      form.name = product.name
      form.categoryId = product.categoryId
      form.basePrice = Number(product.basePrice)
      form.sortOrder = product.sortOrder ?? 0
      form.description = product.description ?? ''
      form.imageKey = product.imageKey ?? ''
      form.specGroupIds = [...(product.specGroupIds ?? [])]
    } else {
      form.name = ''
      form.categoryId = props.defaultCategoryId ?? props.categories[0]?.id ?? null
      form.basePrice = 0
      form.sortOrder = 0
      form.description = ''
      form.imageKey = ''
      form.specGroupIds = props.specGroups.filter((group) => group.enabled === 1).map((group) => group.id)
    }
  }
)

/** 本地预校验：类型白名单 + 2MB 上限（后端双重校验，避免无谓请求）。 */
function beforeUpload(file: File): boolean {
  if (!ACCEPT_TYPES.includes(file.type)) {
    ElMessage.error('仅支持 jpg / png / webp 格式图片')
    return false
  }
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.error('图片大小不能超过 2MB')
    return false
  }
  return true
}

/** 直传后端拿 key，表单只存 key。 */
async function handleUpload(options: UploadRequestOptions): Promise<void> {
  uploading.value = true
  try {
    const result = await uploadImage(options.file)
    form.imageKey = result.key
    ElMessage.success('图片上传成功')
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    uploading.value = false
  }
}

function removeImage(): void {
  form.imageKey = ''
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (saving.value) return

  const payload: ProductSaveRequest = {
    name: form.name.trim(),
    categoryId: form.categoryId as number,
    basePrice: form.basePrice.toFixed(2),
    description: form.description.trim() || undefined,
    imageKey: form.imageKey || undefined,
    sortOrder: form.sortOrder,
    specGroupIds: form.specGroupIds
  }

  saving.value = true
  try {
    if (props.product) {
      await updateProduct(props.product.id, payload)
      ElMessage.success('商品已更新')
    } else {
      await createProduct(payload)
      ElMessage.success('商品已创建（默认上架）')
    }
    emit('update:visible', false)
    emit('saved')
  } catch {
    // 错误提示已由 request 层直显（如 1001 参数错误）
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    :title="title"
    size="520px"
    :close-on-click-modal="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="商品名称" prop="name">
        <el-input v-model="form.name" maxlength="64" placeholder="如：珍珠奶茶" />
      </el-form-item>

      <el-form-item label="分类" prop="categoryId">
        <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
          <el-option
            v-for="category in categories"
            :key="category.id"
            :label="category.name"
            :value="category.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="基础价" prop="basePrice">
        <el-input-number v-model="form.basePrice" :min="0" :max="999999" :precision="2" :step="0.5" />
        <span class="unit">元（杯型 / 加料价差在此基础上累加）</span>
      </el-form-item>

      <el-form-item label="排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        <span class="unit">数字越小越靠前</span>
      </el-form-item>

      <el-form-item label="描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          placeholder="选填，展示在菜单商品名下方"
        />
      </el-form-item>

      <el-form-item label="商品图片">
        <div class="image-field">
          <el-image v-if="previewUrl" :src="previewUrl" fit="cover" class="preview">
            <template #error>
              <div class="preview-error">图片不可用</div>
            </template>
          </el-image>
          <div v-else class="preview placeholder">未上传</div>
          <div class="image-actions">
            <el-upload
              :show-file-list="false"
              :before-upload="beforeUpload"
              :http-request="handleUpload"
              accept="image/jpeg,image/png,image/webp"
            >
              <el-button :loading="uploading">选择图片上传</el-button>
            </el-upload>
            <el-button v-if="form.imageKey" link type="danger" @click="removeImage">移除图片</el-button>
            <div class="unit">jpg / png / webp，单张 ≤ 2MB</div>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="适用规格组">
        <SpecGroupCheck v-model="form.specGroupIds" :groups="specGroups" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.unit {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.image-field {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.preview {
  width: 96px;
  height: 96px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #c0c4cc;
  background: #fafafa;
}

.preview-error {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 12px;
  color: #c0c4cc;
  background: #fafafa;
}

.image-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
</style>
