<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  CircleCheckFilled,
  Clock,
  DataLine,
  Plus,
  RefreshRight,
  WarningFilled
} from '@element-plus/icons-vue'
import { getRuns } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import LoadingState from '@/components/LoadingState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { chainLabels, formatDateTime, formatDuration } from '@/formatters'
import type { WorkflowRun } from '@/types'

const runs = ref<WorkflowRun[]>([])
const loading = ref(true)
const error = ref('')

const sortedRuns = computed(() => [...runs.value].sort((a, b) =>
  new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()))
const recentRuns = computed(() => sortedRuns.value.slice(0, 8))
const activeRuns = computed(() => runs.value.filter((run) => run.state === 'RUNNING' || run.state === 'QUEUED'))
const failedRuns = computed(() => sortedRuns.value.filter((run) => run.state === 'FAILED'))
const succeededRuns = computed(() => runs.value.filter((run) => run.state === 'SUCCEEDED'))
const completedCount = computed(() => failedRuns.value.length + succeededRuns.value.length)
const successRate = computed(() => completedCount.value
  ? Math.round((succeededRuns.value.length / completedCount.value) * 100)
  : 0)
const statusSegments = computed(() => {
  const total = Math.max(runs.value.length, 1)
  return [
    { state: 'SUCCEEDED', value: succeededRuns.value.length, width: succeededRuns.value.length / total * 100 },
    { state: 'RUNNING', value: runs.value.filter((run) => run.state === 'RUNNING').length, width: runs.value.filter((run) => run.state === 'RUNNING').length / total * 100 },
    { state: 'QUEUED', value: runs.value.filter((run) => run.state === 'QUEUED').length, width: runs.value.filter((run) => run.state === 'QUEUED').length / total * 100 },
    { state: 'FAILED', value: failedRuns.value.length, width: failedRuns.value.length / total * 100 }
  ]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    runs.value = await getRuns()
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader
      eyebrow="运行管理"
      title="运行概览"
      description="掌握工作流健康度、近期活动与需要优先处理的问题。"
      refreshable
      :refreshing="loading"
      @refresh="load"
    >
      <template #actions>
        <RouterLink class="button button-primary" to="/runs/new">
          <el-icon><Plus /></el-icon>新建运行
        </RouterLink>
      </template>
    </PageHeader>

    <LoadingState v-if="loading && !runs.length" />
    <div v-else-if="error" class="error-panel" role="alert">
      <strong>无法加载运行数据</strong><span>{{ error }}</span>
      <button class="button" type="button" @click="load">重新加载</button>
    </div>
    <div v-else class="page-content dashboard-content">
      <section class="metric-grid" aria-label="运行指标">
        <article class="metric-card metric-primary">
          <div class="metric-icon"><el-icon><DataLine /></el-icon></div>
          <div><span>累计运行</span><strong>{{ runs.length }}</strong><small>全部工作流记录</small></div>
          <span class="metric-trend">总览</span>
        </article>
        <article class="metric-card metric-info">
          <div class="metric-icon"><el-icon><RefreshRight /></el-icon></div>
          <div><span>正在进行</span><strong>{{ activeRuns.length }}</strong><small>运行中与排队中</small></div>
          <span class="metric-trend">{{ activeRuns.length ? '实时' : '空闲' }}</span>
        </article>
        <article class="metric-card metric-success">
          <div class="metric-icon"><el-icon><CircleCheckFilled /></el-icon></div>
          <div><span>任务成功率</span><strong>{{ successRate }}%</strong><small>{{ completedCount }} 次已结束运行</small></div>
          <span class="metric-trend">稳定性</span>
        </article>
        <article class="metric-card metric-danger">
          <div class="metric-icon"><el-icon><WarningFilled /></el-icon></div>
          <div><span>需要关注</span><strong>{{ failedRuns.length }}</strong><small>失败运行待处理</small></div>
          <span class="metric-trend">{{ failedRuns.length ? '待处理' : '正常' }}</span>
        </article>
      </section>

      <section class="dashboard-layout">
        <article class="surface recent-runs-card">
          <header class="surface-header">
            <div><p class="section-kicker">最近活动</p><h2>最近运行</h2></div>
            <RouterLink class="text-link" to="/history">查看全部<el-icon><ArrowRight /></el-icon></RouterLink>
          </header>
          <EmptyState
            v-if="!recentRuns.length"
            title="还没有运行记录"
            description="创建第一个任务后，这里会展示实时状态与运行耗时。"
          >
            <RouterLink class="button button-primary" to="/runs/new">创建运行</RouterLink>
          </EmptyState>
          <div v-else class="responsive-table">
            <table>
              <thead><tr><th>运行</th><th>工作流</th><th>状态</th><th>创建时间</th><th>耗时</th><th><span class="visually-hidden">操作</span></th></tr></thead>
              <tbody>
                <tr v-for="run in recentRuns" :key="run.id">
                  <td><RouterLink class="run-number" :to="`/runs/${run.id}`">#{{ run.id }}</RouterLink></td>
                  <td><strong class="table-primary">{{ chainLabels[run.chainId] ?? run.chainId }}</strong><small>{{ run.mode === 'rerun' ? '重跑' : '全量' }}</small></td>
                  <td><StatusBadge :state="run.state" /></td>
                  <td>{{ formatDateTime(run.createdAt) }}</td>
                  <td class="numeric">{{ formatDuration(run) }}</td>
                  <td><RouterLink class="row-action" :to="`/runs/${run.id}`" :aria-label="`查看运行 ${run.id}`"><el-icon><ArrowRight /></el-icon></RouterLink></td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <aside class="dashboard-side">
          <article class="surface health-card">
            <header class="surface-header compact">
              <div><p class="section-kicker">系统健康度</p><h2>状态分布</h2></div>
              <span class="health-score">{{ successRate }}%</span>
            </header>
            <div class="status-bar" aria-label="运行状态分布">
              <span v-for="segment in statusSegments" :key="segment.state" :data-state="segment.state" :style="{ width: `${segment.width}%` }"></span>
            </div>
            <ul class="status-legend">
              <li v-for="segment in statusSegments" :key="segment.state">
                <span><i :data-state="segment.state"></i>{{ { SUCCEEDED: '成功', RUNNING: '运行中', QUEUED: '排队', FAILED: '失败' }[segment.state] }}</span>
                <strong>{{ segment.value }}</strong>
              </li>
            </ul>
          </article>

          <article class="surface attention-card">
            <header class="surface-header compact">
              <div><p class="section-kicker">处理队列</p><h2>需要关注</h2></div>
              <span class="attention-count">{{ failedRuns.length }}</span>
            </header>
            <p v-if="!failedRuns.length" class="all-clear"><el-icon><CircleCheckFilled /></el-icon>当前没有失败运行，系统状态良好。</p>
            <ul v-else class="attention-list">
              <li v-for="run in failedRuns.slice(0, 3)" :key="run.id">
                <span class="attention-icon"><el-icon><WarningFilled /></el-icon></span>
                <RouterLink :to="`/runs/${run.id}`">
                  <strong>{{ chainLabels[run.chainId] ?? run.chainId }} · #{{ run.id }}</strong>
                  <span>{{ run.failureMessage || '未记录失败原因' }}</span>
                  <time><el-icon><Clock /></el-icon>{{ formatDateTime(run.createdAt) }}</time>
                </RouterLink>
              </li>
            </ul>
          </article>
        </aside>
      </section>
    </div>
  </div>
</template>
