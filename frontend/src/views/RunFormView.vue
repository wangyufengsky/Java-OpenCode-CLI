<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Check,
  Connection,
  DocumentChecked,
  Files,
  Loading,
  Promotion,
  Right
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createRun, getChains, getDefaults, getRunConfig } from '@/api'
import DynamicConfigForm from '@/components/DynamicConfigForm.vue'
import PageHeader from '@/components/PageHeader.vue'
import { chainLabels } from '@/formatters'

const route = useRoute()
const router = useRouter()
const chains = ref<string[]>([])
const chainId = ref(String(route.query.chainId ?? 'git-code-contribution-report'))
const mode = ref(String(route.query.mode ?? 'full') === 'rerun' ? 'rerun' : 'full')
const rerunType = ref(String(route.query.rerunType ?? ''))
const rerunId = ref(String(route.query.rerunId ?? ''))
const runDate = ref('')
const config = ref<Record<string, unknown>>({})
const loadingConfig = ref(true)
const submitting = ref(false)
const error = ref('')
const copyFrom = computed(() => Number(route.query.copyFrom) || null)
const copiedFromLabel = ref<number | null>(null)

const definition = computed(() => window.ConsoleCommon?.chainConfigDefinitions[chainId.value])
const rerunTypes = computed(() => window.ConsoleCommon?.rerunTypeDefinitions[chainId.value] ?? [])
const selectedRerun = computed(() => rerunTypes.value.find((item) => item.value === rerunType.value))
const requiredFields = computed(() => definition.value?.fields.filter((field) => field.required) ?? [])
const missingFields = computed(() => requiredFields.value.filter((field) => {
  const value = config.value[field.key]
  if (field.type === 'checkbox') return value !== true
  if (Array.isArray(value)) return value.length === 0
  return value === null || value === undefined || String(value).trim() === ''
}))
const ready = computed(() =>
  !loadingConfig.value &&
  !missingFields.value.length &&
  (mode.value !== 'rerun' || Boolean(rerunType.value)) &&
  (!selectedRerun.value?.requiresId || Boolean(rerunId.value.trim())))
const projectName = computed(() => String(
  config.value['project.name'] ?? config.value['project.id'] ?? '尚未填写'
))
const scope = computed(() => String(
  config.value['project.repo'] ?? config.value['paths.repo'] ?? config.value['new-project'] ?? config.value['java-root'] ?? '尚未填写'
))
const output = computed(() => String(
  config.value['paths.out'] ?? config.value.out ?? '使用链路默认位置'
))

function chainDescription(chain: string): string {
  return window.ConsoleCommon?.chainConfigDefinitions[chain]?.description ?? chain
}

watch(chainId, async (value, previous) => {
  if (value === previous) return
  mode.value = 'full'
  rerunType.value = ''
  rerunId.value = ''
  await loadDefaults()
  syncUrl()
})
watch(mode, (value) => {
  if (value !== 'rerun') {
    rerunType.value = ''
    rerunId.value = ''
  } else if (!rerunType.value) {
    rerunType.value = rerunTypes.value[0]?.value ?? ''
  }
})
watch(rerunType, () => {
  if (!selectedRerun.value?.requiresId) rerunId.value = ''
})

async function loadDefaults() {
  loadingConfig.value = true
  error.value = ''
  try {
    config.value = await getDefaults(chainId.value)
  } catch (reason) {
    error.value = (reason as Error).message
    config.value = {}
  } finally {
    loadingConfig.value = false
  }
}

async function loadInitial() {
  loadingConfig.value = true
  try {
    chains.value = await getChains()
    if (!chains.value.includes(chainId.value)) chainId.value = chains.value[0] ?? ''
    if (copyFrom.value) {
      const source = await getRunConfig(copyFrom.value)
      copiedFromLabel.value = source.sourceRunId
      chainId.value = source.chainId
      mode.value = source.mode
      rerunType.value = source.rerunType ?? ''
      rerunId.value = source.rerunId ?? ''
      runDate.value = source.runDate ?? ''
      config.value = source.config
    } else {
      await loadDefaults()
    }
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loadingConfig.value = false
  }
}

function syncUrl() {
  const query: Record<string, string> = { chainId: chainId.value }
  if (mode.value === 'rerun') query.mode = mode.value
  if (rerunType.value) query.rerunType = rerunType.value
  if (rerunId.value) query.rerunId = rerunId.value
  void router.replace({ query })
}

async function submit() {
  error.value = ''
  if (!ready.value) {
    error.value = missingFields.value.length
      ? `请先填写：${missingFields.value.map((field) => field.label).join('、')}`
      : '请完整填写重跑参数'
    await nextTick()
    document.querySelector<HTMLElement>('.config-field input:invalid, .config-field textarea:invalid')?.focus()
    return
  }
  submitting.value = true
  try {
    const id = await createRun({
      chainId: chainId.value,
      mode: mode.value,
      rerunType: mode.value === 'rerun' ? rerunType.value : null,
      rerunId: mode.value === 'rerun' && selectedRerun.value?.requiresId ? rerunId.value.trim() : null,
      runDate: runDate.value || null,
      config: config.value,
      agentBridge: null
    })
    ElMessage.success(`运行 #${id} 已提交`)
    await router.push(`/runs/${id}`)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    submitting.value = false
  }
}

onMounted(loadInitial)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="运行管理" title="新建运行" description="选择工作流、填写参数，并在提交前完成必要校验。">
      <template #actions>
        <RouterLink class="button button-quiet" to="/"><el-icon><ArrowLeft /></el-icon>返回概览</RouterLink>
      </template>
    </PageHeader>

    <div class="page-content new-run-content">
      <nav class="step-navigation" aria-label="创建运行步骤">
        <span class="is-complete"><i><el-icon><Check /></el-icon></i><strong>选择工作流</strong><small>确定任务类型</small></span>
        <b aria-hidden="true"></b>
        <span :class="{ 'is-complete': ready, 'is-active': !ready }"><i>2</i><strong>填写配置</strong><small>完成必要参数</small></span>
        <b aria-hidden="true"></b>
        <span :class="{ 'is-active': ready }"><i>3</i><strong>确认并运行</strong><small>检查运行摘要</small></span>
      </nav>

      <div v-if="copiedFromLabel" class="copy-banner" role="status">
        <el-icon><Files /></el-icon>
        <span><strong>复制自运行 #{{ copiedFromLabel }}</strong>已载入原运行配置，请核对路径与日期后提交。</span>
      </div>
      <div v-if="error" class="form-error" role="alert" aria-live="polite">{{ error }}</div>

      <form class="run-builder" novalidate @submit.prevent="submit">
        <div class="builder-main">
          <section class="surface workflow-section">
            <header class="surface-header">
              <div><p class="section-kicker">步骤 1</p><h2>选择工作流</h2><span>切换后载入该工作流的默认配置。</span></div>
            </header>
            <div class="workflow-grid">
              <button
                v-for="chain in chains"
                :key="chain"
                class="workflow-card"
                :class="{ active: chainId === chain }"
                type="button"
                :aria-pressed="chainId === chain"
                @click="chainId = chain"
              >
                <span class="workflow-icon" aria-hidden="true"><el-icon><DocumentChecked /></el-icon></span>
                <span><strong>{{ chainLabels[chain] ?? chain }}</strong><small>{{ chainDescription(chain) }}</small></span>
                <el-icon class="workflow-check"><Check /></el-icon>
              </button>
            </div>
          </section>

          <section class="surface execution-section">
            <header class="surface-header">
              <div><p class="section-kicker">运行策略</p><h2>运行方式</h2><span>全量执行或按链路契约重跑指定任务。</span></div>
            </header>
            <div class="form-grid">
              <label>模式
                <select v-model="mode" name="mode">
                  <option value="full">全量运行</option><option value="rerun">重跑指定任务</option>
                </select>
              </label>
              <label v-if="mode === 'rerun'">重跑类型
                <select v-model="rerunType" name="rerunType" required>
                  <option v-for="item in rerunTypes" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select>
              </label>
              <label v-if="mode === 'rerun' && selectedRerun?.requiresId">重跑编号
                <input v-model="rerunId" name="rerunId" autocomplete="off" required :placeholder="selectedRerun.idPlaceholder + '…'">
              </label>
              <label>运行日期
                <input v-model="runDate" name="runDate" type="date">
              </label>
            </div>
          </section>

          <section id="chain-config" class="surface config-section">
            <header class="surface-header">
              <div><p class="section-kicker">步骤 2</p><h2>链路配置</h2><span>{{ definition?.description }}</span></div>
              <span class="field-count">{{ requiredFields.length }} 个必填项</span>
            </header>
            <div v-if="loadingConfig" class="config-loading" role="status"><el-icon class="rotating"><Loading /></el-icon>正在载入默认配置…</div>
            <DynamicConfigForm v-else v-model="config" :chain-id="chainId" :disabled="submitting" />
          </section>
        </div>

        <aside class="surface run-summary">
          <header>
            <div><p class="section-kicker">步骤 3</p><h2>运行摘要</h2></div>
            <span class="readiness" :class="{ ready }">{{ ready ? '可以提交' : '配置中' }}</span>
          </header>
          <dl>
            <div><dt>工作流</dt><dd>{{ chainLabels[chainId] ?? chainId }}</dd></div>
            <div><dt>运行方式</dt><dd>{{ mode === 'rerun' ? `重跑 · ${selectedRerun?.label ?? '待选择'}` : '全量运行' }}</dd></div>
            <div><dt>项目</dt><dd>{{ projectName }}</dd></div>
            <div><dt>代码范围</dt><dd class="path-value">{{ scope }}</dd></div>
            <div><dt>输出目录</dt><dd class="path-value">{{ output }}</dd></div>
            <div><dt>校验策略</dt><dd>路径预检 · 失败即停止</dd></div>
          </dl>
          <div class="submission-note">
            <el-icon><Connection /></el-icon>
            <span><strong>提交后创建 1 次运行</strong>可在详情页查看阶段进度、任务状态与实时事件。</span>
          </div>
          <a class="button button-quiet summary-preview" href="#chain-config">检查链路配置<el-icon><Right /></el-icon></a>
          <button class="button button-primary submit-button" type="submit" :disabled="submitting || loadingConfig">
            <el-icon :class="{ rotating: submitting }"><component :is="submitting ? Loading : Promotion" /></el-icon>
            {{ submitting ? '正在提交…' : '确认并运行' }}
          </button>
        </aside>
      </form>
    </div>
  </div>
</template>
