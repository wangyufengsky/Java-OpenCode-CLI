<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearRuns, deleteRun, getRuns, rerun } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import LoadingState from '@/components/LoadingState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { chainLabels, formatDateTime, formatDuration } from '@/formatters'
import type { WorkflowRun } from '@/types'

const route = useRoute()
const router = useRouter()
const runs = ref<WorkflowRun[]>([])
const loading = ref(true)
const query = ref(String(route.query.q ?? ''))
const state = ref(String(route.query.state ?? ''))
const chainId = ref(String(route.query.chainId ?? ''))
const from = ref(String(route.query.from ?? ''))
const until = ref(String(route.query.until ?? ''))
const page = ref(Math.max(1, Number(route.query.page) || 1))
const pageSize = 12

const chains = computed(() => Array.from(new Set(runs.value.map((run) => run.chainId))))
const filteredRuns = computed(() => {
  const normalized = query.value.trim().toLocaleLowerCase('zh-CN')
  return [...runs.value]
    .filter((run) => !normalized || [
      String(run.id), run.chainId, chainLabels[run.chainId], run.configPath, run.failureMessage
    ].some((value) => String(value ?? '').toLocaleLowerCase('zh-CN').includes(normalized)))
    .filter((run) => !state.value || run.state === state.value)
    .filter((run) => !chainId.value || run.chainId === chainId.value)
    .filter((run) => !from.value || run.createdAt.slice(0, 10) >= from.value)
    .filter((run) => !until.value || run.createdAt.slice(0, 10) <= until.value)
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
})
const totalPages = computed(() => Math.max(1, Math.ceil(filteredRuns.value.length / pageSize)))
const pageRuns = computed(() => filteredRuns.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const terminalCount = computed(() => runs.value.filter((run) => run.state === 'SUCCEEDED' || run.state === 'FAILED').length)

watch([query, state, chainId, from, until], () => {
  page.value = 1
  syncUrl()
})
watch(page, syncUrl)
watch(totalPages, (value) => { if (page.value > value) page.value = value })

function syncUrl() {
  const next: Record<string, string> = {}
  if (query.value) next.q = query.value
  if (state.value) next.state = state.value
  if (chainId.value) next.chainId = chainId.value
  if (from.value) next.from = from.value
  if (until.value) next.until = until.value
  if (page.value > 1) next.page = String(page.value)
  void router.replace({ query: next })
}

async function load() {
  loading.value = true
  try {
    runs.value = await getRuns()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  query.value = ''
  state.value = ''
  chainId.value = ''
  from.value = ''
  until.value = ''
}

async function handleRerun(run: WorkflowRun) {
  try {
    await ElMessageBox.confirm(`将使用运行 #${run.id} 的保存配置创建一次新运行。`, '确认一键重跑', {
      confirmButtonText: '创建运行',
      cancelButtonText: '取消',
      type: 'info'
    })
    const id = await rerun(run.id)
    ElMessage.success(`已创建运行 #${id}`)
    await router.push(`/runs/${id}`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error((error as Error).message)
  }
}

async function handleDelete(run: WorkflowRun) {
  try {
    await ElMessageBox.confirm(`运行 #${run.id} 的历史、任务状态和事件将被清理。`, '清理运行记录', {
      confirmButtonText: '确认清理',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteRun(run.id)
    ElMessage.success('运行记录已清理')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error((error as Error).message)
  }
}

async function handleClear() {
  try {
    await ElMessageBox.confirm(`将清理当前保存的 ${terminalCount.value} 条已结束运行；进行中的运行会被保留。`, '清理全部历史', {
      confirmButtonText: '清理已结束运行',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const deleted = await clearRuns()
    ElMessage.success(`已清理 ${deleted} 条运行记录`)
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error((error as Error).message)
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="运行管理" title="运行历史" description="筛选、追踪并复用已保存的工作流运行。">
      <template #actions>
        <button class="button button-danger-quiet" type="button" :disabled="!terminalCount" @click="handleClear">
          <el-icon><Delete /></el-icon>清理历史
        </button>
        <RouterLink class="button button-primary" to="/runs/new"><el-icon><Plus /></el-icon>新建运行</RouterLink>
      </template>
    </PageHeader>

    <div class="page-content">
      <section class="surface filter-surface">
        <div class="search-field">
          <el-icon><Search /></el-icon>
          <label class="visually-hidden" for="history-query">搜索运行</label>
          <input id="history-query" v-model="query" name="q" type="search" autocomplete="off" placeholder="搜索编号、工作流或配置路径…">
        </div>
        <label>状态
          <select v-model="state" name="state">
            <option value="">全部状态</option><option value="QUEUED">排队中</option><option value="RUNNING">运行中</option>
            <option value="SUCCEEDED">已成功</option><option value="FAILED">已失败</option>
          </select>
        </label>
        <label>工作流
          <select v-model="chainId" name="chainId">
            <option value="">全部工作流</option><option v-for="chain in chains" :key="chain" :value="chain">{{ chainLabels[chain] ?? chain }}</option>
          </select>
        </label>
        <label>开始日期<input v-model="from" name="from" type="date"></label>
        <label>结束日期<input v-model="until" name="until" type="date"></label>
        <button class="button button-quiet" type="button" @click="resetFilters">重置</button>
      </section>

      <LoadingState v-if="loading" />
      <section v-else class="surface history-surface">
        <header class="surface-header">
          <div><p class="section-kicker">运行归档</p><h2>{{ filteredRuns.length }} 条运行记录</h2></div>
          <span class="result-caption">第 {{ page }} / {{ totalPages }} 页</span>
        </header>
        <EmptyState
          v-if="!pageRuns.length"
          title="没有符合条件的运行"
          description="调整筛选条件，或重置筛选查看所有记录。"
        >
          <button class="button" type="button" @click="resetFilters">重置筛选</button>
        </EmptyState>
        <div v-else class="responsive-table">
          <table>
            <thead><tr><th>运行</th><th>工作流</th><th>模式</th><th>状态</th><th>创建时间</th><th>耗时</th><th>失败原因</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="run in pageRuns" :key="run.id">
                <td><RouterLink class="run-number" :to="`/runs/${run.id}`">#{{ run.id }}</RouterLink></td>
                <td><strong class="table-primary">{{ chainLabels[run.chainId] ?? run.chainId }}</strong><small>{{ run.chainId }}</small></td>
                <td>{{ run.mode === 'rerun' ? '重跑' : '全量' }}</td>
                <td><StatusBadge :state="run.state" /></td>
                <td>{{ formatDateTime(run.createdAt) }}</td>
                <td class="numeric">{{ formatDuration(run) }}</td>
                <td><span class="failure-cell">{{ run.failureMessage || '—' }}</span></td>
                <td>
                  <div class="table-actions">
                    <RouterLink class="text-button" :to="`/runs/${run.id}`">详情</RouterLink>
                    <RouterLink class="text-button" :to="{ path: '/runs/new', query: { copyFrom: run.id } }">复制</RouterLink>
                    <button v-if="run.state === 'FAILED' || run.state === 'SUCCEEDED'" class="text-button" type="button" @click="handleRerun(run)">重跑</button>
                    <button v-if="run.state === 'FAILED' || run.state === 'SUCCEEDED'" class="text-button danger" type="button" @click="handleDelete(run)">清理</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav v-if="pageRuns.length" class="pagination" aria-label="运行历史分页">
          <button class="button" type="button" :disabled="page <= 1" @click="page--">上一页</button>
          <span>第 <strong>{{ page }}</strong> 页，共 {{ totalPages }} 页</span>
          <button class="button" type="button" :disabled="page >= totalPages" @click="page++">下一页</button>
        </nav>
      </section>
    </div>
  </div>
</template>
