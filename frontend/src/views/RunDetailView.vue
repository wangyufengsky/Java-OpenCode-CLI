<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  CopyDocument,
  Promotion,
  Refresh,
  WarningFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getRunSnapshot } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import LoadingState from '@/components/LoadingState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { chainLabels, eventLabels, formatDateTime, phaseLabels, stateLabels } from '@/formatters'
import type { RunEvent, RunSnapshot } from '@/types'

const route = useRoute()
const router = useRouter()
const runId = Number(route.params.id)
const snapshot = ref<RunSnapshot | null>(null)
const loading = ref(true)
const connection = ref<'connecting' | 'live' | 'polling' | 'closed'>('connecting')
const eventFilter = ref(String(route.query.eventFilter ?? 'all'))
const taskFilter = ref('all')
let eventSource: EventSource | null = null
let pollTimer: number | null = null

const run = computed(() => snapshot.value?.run)
const summary = computed(() => snapshot.value?.summary)
const terminal = computed(() => run.value?.state === 'SUCCEEDED' || run.value?.state === 'FAILED')
const filteredEvents = computed(() => (snapshot.value?.events ?? []).filter((event) => {
  if (eventFilter.value === 'failed') return event.eventType.includes('FAILED')
  if (eventFilter.value === 'task') return event.eventType.startsWith('TASK_')
  if (eventFilter.value === 'stage') return !event.eventType.startsWith('TASK_')
  return true
}))
const filteredTasks = computed(() => (snapshot.value?.tasks ?? [])
  .filter((task) => taskFilter.value === 'all' || task.state === taskFilter.value)
  .sort((a, b) => (a.state === 'FAILED' ? -1 : b.state === 'FAILED' ? 1 : a.taskKey.localeCompare(b.taskKey))))
const stages = computed(() => {
  const state = run.value?.state
  const hasTasks = Boolean(snapshot.value?.tasks.length)
  return [
    { name: '提交', description: '配置已保存', done: true, active: false },
    { name: '排队', description: state === 'QUEUED' ? '等待执行资源' : '已进入队列', done: state !== 'QUEUED', active: state === 'QUEUED' },
    { name: '执行', description: phaseLabels[run.value?.phase ?? ''] ?? run.value?.phase ?? '等待开始', done: state === 'SUCCEEDED' || state === 'FAILED', active: state === 'RUNNING' },
    { name: '任务', description: hasTasks ? `${summary.value?.succeededTasks ?? 0}/${summary.value?.totalTasks ?? 0} 已成功` : '尚无任务状态', done: state === 'SUCCEEDED', active: state === 'RUNNING' && hasTasks },
    { name: '完成', description: state ? stateLabels[state] : '等待完成', done: state === 'SUCCEEDED', active: state === 'FAILED' }
  ]
})

async function refresh(afterEventId = 0) {
  try {
    const next = await getRunSnapshot(runId, afterEventId)
    if (afterEventId && snapshot.value) {
      const known = new Set(snapshot.value.events.map((event) => event.id))
      next.events = [...snapshot.value.events, ...next.events.filter((event) => !known.has(event.id))]
    }
    snapshot.value = next
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function startLiveUpdates() {
  if (terminal.value) {
    connection.value = 'closed'
    return
  }
  eventSource = new EventSource(`/api/runs/${runId}/events`)
  eventSource.onopen = () => { connection.value = 'live' }
  eventSource.onmessage = async () => {
    const lastId = Math.max(0, ...(snapshot.value?.events.map((event) => event.id) ?? [0]))
    await refresh(lastId)
    if (terminal.value) stopLiveUpdates()
  }
  eventSource.onerror = () => {
    eventSource?.close()
    eventSource = null
    connection.value = 'polling'
    schedulePoll()
  }
}

function schedulePoll() {
  if (terminal.value || pollTimer !== null) return
  pollTimer = window.setTimeout(async () => {
    pollTimer = null
    await refresh()
    schedulePoll()
  }, 5000)
}

function stopLiveUpdates() {
  eventSource?.close()
  eventSource = null
  if (pollTimer !== null) window.clearTimeout(pollTimer)
  pollTimer = null
  connection.value = 'closed'
}

function copyConfig() {
  void router.push({ path: '/runs/new', query: { copyFrom: runId } })
}

function rerunFailed() {
  const action = snapshot.value?.rerunAction
  if (!run.value || !action?.available) return
  void router.push({
    path: '/runs/new',
    query: {
      chainId: run.value.chainId,
      mode: 'rerun',
      rerunType: action.rerunType,
      rerunId: action.rerunId
    }
  })
}

function eventTone(event: RunEvent): string {
  if (event.eventType.includes('FAILED')) return 'danger'
  if (event.eventType.includes('SUCCEEDED')) return 'success'
  if (event.eventType.includes('STARTED') || event.eventType.includes('RUNNING')) return 'primary'
  return 'neutral'
}

onMounted(async () => {
  await refresh()
  startLiveUpdates()
})
onBeforeUnmount(stopLiveUpdates)
</script>

<template>
  <div class="page">
    <PageHeader
      eyebrow="运行详情"
      :title="run ? `运行 #${run.id}` : '运行详情'"
      :description="run ? `${chainLabels[run.chainId] ?? run.chainId} · ${run.mode === 'rerun' ? '重跑' : '全量运行'}` : '正在载入运行数据…'"
      refreshable
      :refreshing="loading"
      @refresh="refresh()"
    >
      <template #actions>
        <RouterLink class="button button-quiet" to="/history"><el-icon><ArrowLeft /></el-icon>返回历史</RouterLink>
        <button v-if="run" class="button" type="button" @click="copyConfig"><el-icon><CopyDocument /></el-icon>复制配置</button>
        <button v-if="snapshot?.rerunAction.available" class="button button-primary" type="button" @click="rerunFailed">
          <el-icon><Promotion /></el-icon>重跑失败任务
        </button>
      </template>
    </PageHeader>

    <LoadingState v-if="loading && !snapshot" />
    <div v-else-if="snapshot && run && summary" class="page-content detail-content">
      <section class="run-context">
        <StatusBadge :state="run.state" />
        <span>{{ chainLabels[run.chainId] ?? run.chainId }}</span>
        <span>·</span>
        <span>{{ phaseLabels[run.phase] ?? run.phase }}</span>
        <span class="connection-state" :data-state="connection">
          <i aria-hidden="true"></i>{{ { connecting: '正在连接…', live: '实时更新', polling: '轮询恢复', closed: '运行已结束' }[connection] }}
        </span>
      </section>

      <section class="metric-grid detail-metrics" aria-label="运行摘要">
        <article class="metric-card metric-primary"><div><span>当前状态</span><strong class="metric-text">{{ stateLabels[run.state] }}</strong><small>{{ phaseLabels[run.phase] ?? run.phase }}</small></div></article>
        <article class="metric-card metric-info"><div><span>任务进度</span><strong>{{ summary.succeededTasks }}/{{ summary.totalTasks }}</strong><small>已成功 / 总任务</small></div></article>
        <article class="metric-card metric-danger"><div><span>失败任务</span><strong>{{ summary.failedTasks }}</strong><small>{{ summary.failedTasks ? '需要优先处理' : '当前无失败' }}</small></div></article>
        <article class="metric-card metric-success"><div><span>运行耗时</span><strong>{{ summary.durationSeconds }}</strong><small>秒</small></div></article>
      </section>

      <section class="surface progress-surface">
        <header class="surface-header"><div><p class="section-kicker">工作流阶段</p><h2>运行进度</h2></div><span>{{ phaseLabels[run.phase] ?? run.phase }}</span></header>
        <ol class="progress-steps">
          <li v-for="(stage, index) in stages" :key="stage.name" :class="{ done: stage.done, active: stage.active }">
            <i><span v-if="stage.done">✓</span><span v-else>{{ index + 1 }}</span></i>
            <strong>{{ stage.name }}</strong><small>{{ stage.description }}</small>
          </li>
        </ol>
      </section>

      <section v-if="run.state === 'FAILED' || summary.failureMessage" class="failure-summary" role="alert">
        <span class="failure-summary-icon"><el-icon><WarningFilled /></el-icon></span>
        <div>
          <strong>运行未成功完成</strong>
          <p>{{ summary.failureMessage || run.failureMessage || '未记录运行失败原因' }}</p>
          <dl>
            <div><dt>失败任务</dt><dd>{{ summary.failedTaskKey || '未记录' }}</dd></div>
            <div><dt>最后错误</dt><dd>{{ summary.lastErrorMessage || '未记录' }}</dd></div>
          </dl>
          <p v-if="snapshot.rerunAction.visible && !snapshot.rerunAction.available" class="rerun-reason">{{ snapshot.rerunAction.reason }}</p>
        </div>
      </section>

      <section class="detail-grid">
        <article class="surface event-surface">
          <header class="surface-header">
            <div><p class="section-kicker">实时日志</p><h2>事件流</h2></div>
            <label>事件类别
              <select v-model="eventFilter" name="eventFilter">
                <option value="all">全部事件</option><option value="stage">阶段事件</option>
                <option value="task">任务事件</option><option value="failed">错误事件</option>
              </select>
            </label>
          </header>
          <EmptyState v-if="!filteredEvents.length" title="当前筛选下暂无事件" description="运行产生新事件后会自动显示在这里。" />
          <ol v-else class="event-timeline">
            <li v-for="event in filteredEvents" :key="event.id" :data-tone="eventTone(event)">
              <i aria-hidden="true"></i>
              <div><time>{{ formatDateTime(event.createdAt) }}</time><strong>{{ eventLabels[event.eventType] ?? event.eventType }}</strong><p>{{ event.message }}</p></div>
            </li>
          </ol>
        </article>

        <aside class="surface task-surface">
          <header class="surface-header">
            <div><p class="section-kicker">执行单元</p><h2>任务状态</h2></div>
            <label>状态
              <select v-model="taskFilter" name="taskFilter">
                <option value="all">全部</option><option value="QUEUED">排队中</option><option value="RUNNING">运行中</option>
                <option value="SUCCEEDED">已成功</option><option value="FAILED">已失败</option>
              </select>
            </label>
          </header>
          <EmptyState v-if="!filteredTasks.length" title="暂无任务状态" description="工作流拆分任务后会在这里展示执行进度。" />
          <ul v-else class="task-list">
            <li v-for="task in filteredTasks" :key="task.taskKey">
              <span><strong>{{ task.taskName }}</strong><small>{{ task.taskKey }}</small></span>
              <StatusBadge :state="task.state" />
              <span class="task-phase">{{ phaseLabels[task.phase] ?? task.phase }}</span>
              <p v-if="task.errorMessage">{{ task.errorMessage }}</p>
            </li>
          </ul>
        </aside>
      </section>
    </div>
  </div>
</template>
