<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  Calendar,
  Close,
  CopyDocument,
  EditPen,
  Plus,
  Search
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getChains, getDefaults, getSchedules, saveSchedule, setScheduleEnabled } from '@/api'
import DynamicConfigForm from '@/components/DynamicConfigForm.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingState from '@/components/LoadingState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { chainLabels, formatDateTime, scheduleFrequency } from '@/formatters'
import type { SchedulePayload, WorkflowSchedule } from '@/types'

const schedules = ref<WorkflowSchedule[]>([])
const chains = ref<string[]>([])
const loading = ref(true)
const query = ref('')
const status = ref('all')
const drawerOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const config = ref<Record<string, unknown>>({})
const form = reactive({
  chainId: 'git-code-contribution-report',
  mode: 'full',
  rerunType: '',
  rerunId: '',
  runDate: '',
  frequency: 'daily',
  dayOfWeek: 1,
  runTime: '06:00',
  runAt: '',
  enabled: true
})

const filteredSchedules = computed(() => {
  const normalized = query.value.trim().toLocaleLowerCase('zh-CN')
  return schedules.value.filter((schedule) => {
    const matchesQuery = !normalized || [
      schedule.id,
      schedule.chainId,
      chainLabels[schedule.chainId],
      scheduleFrequency(schedule)
    ].some((value) => String(value ?? '').toLocaleLowerCase('zh-CN').includes(normalized))
    const matchesStatus = status.value === 'all'
      || (status.value === 'enabled' && schedule.enabled)
      || (status.value === 'disabled' && !schedule.enabled)
    return matchesQuery && matchesStatus
  })
})
const rerunTypes = computed(() => window.ConsoleCommon?.rerunTypeDefinitions[form.chainId] ?? [])
const selectedRerun = computed(() => rerunTypes.value.find((item) => item.value === form.rerunType))
const chainDescription = computed(() =>
  window.ConsoleCommon?.chainConfigDefinitions[form.chainId]?.description ?? form.chainId)

watch(() => form.chainId, async () => {
  if (!drawerOpen.value || editingId.value !== null) return
  config.value = await getDefaults(form.chainId)
  form.rerunType = rerunTypes.value[0]?.value ?? ''
  form.rerunId = ''
})
watch(() => form.mode, (value) => {
  if (value === 'full') {
    form.rerunType = ''
    form.rerunId = ''
  } else if (!form.rerunType) {
    form.rerunType = rerunTypes.value[0]?.value ?? ''
  }
})
watch(() => form.rerunType, () => {
  if (!selectedRerun.value?.requiresId) form.rerunId = ''
})
watch(drawerOpen, (open) => {
  document.body.classList.toggle('drawer-open', open)
})

async function load() {
  loading.value = true
  try {
    const [scheduleList, chainList] = await Promise.all([getSchedules(), getChains()])
    schedules.value = scheduleList
    chains.value = chainList
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    chainId: chains.value[0] ?? 'git-code-contribution-report',
    mode: 'full',
    rerunType: '',
    rerunId: '',
    runDate: '',
    frequency: 'daily',
    dayOfWeek: 1,
    runTime: '06:00',
    runAt: '',
    enabled: true
  })
}

async function createSchedule() {
  editingId.value = null
  resetForm()
  drawerOpen.value = true
  try {
    config.value = await getDefaults(form.chainId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function fillFromSchedule(schedule: WorkflowSchedule, copy: boolean) {
  editingId.value = copy ? null : schedule.id
  Object.assign(form, {
    chainId: schedule.chainId,
    mode: schedule.mode,
    rerunType: schedule.rerunType ?? '',
    rerunId: schedule.rerunId ?? '',
    runDate: schedule.runDate ?? '',
    frequency: schedule.frequency.toLocaleLowerCase(),
    dayOfWeek: schedule.dayOfWeek ?? 1,
    runTime: schedule.runTime?.slice(0, 5) ?? '06:00',
    runAt: schedule.runAt?.slice(0, 16) ?? '',
    enabled: copy ? true : schedule.enabled
  })
  config.value = structuredClone(schedule.config)
  drawerOpen.value = true
}

async function toggle(schedule: WorkflowSchedule) {
  const original = schedule.enabled
  schedule.enabled = !original
  try {
    const updated = await setScheduleEnabled(schedule.id, schedule.enabled)
    Object.assign(schedule, updated)
    ElMessage.success(schedule.enabled ? '计划已启用' : '计划已停用')
  } catch (error) {
    schedule.enabled = original
    ElMessage.error((error as Error).message)
  }
}

async function submit() {
  if (form.mode === 'rerun' && !form.rerunType) {
    ElMessage.warning('请选择重跑类型')
    return
  }
  if (form.mode === 'rerun' && selectedRerun.value?.requiresId && !form.rerunId.trim()) {
    ElMessage.warning('请填写重跑编号')
    return
  }
  if (form.frequency === 'once' && !form.runAt) {
    ElMessage.warning('请选择一次性执行时间')
    return
  }
  saving.value = true
  const payload: SchedulePayload = {
    chainId: form.chainId,
    mode: form.mode,
    rerunType: form.mode === 'rerun' ? form.rerunType : null,
    rerunId: form.mode === 'rerun' && selectedRerun.value?.requiresId ? form.rerunId.trim() : null,
    runDate: form.runDate || null,
    config: config.value,
    frequency: form.frequency,
    dayOfWeek: form.frequency === 'weekly' ? form.dayOfWeek : null,
    runTime: form.frequency === 'once' ? null : form.runTime,
    runAt: form.frequency === 'once' ? form.runAt : null,
    enabled: form.enabled
  }
  try {
    await saveSchedule(editingId.value, payload)
    ElMessage.success(editingId.value === null ? '计划已创建' : '计划已保存')
    drawerOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="自动化" title="定时任务" description="集中管理工作流执行计划、下次触发时间与启停状态。">
      <template #actions>
        <button class="button button-primary" type="button" @click="createSchedule"><el-icon><Plus /></el-icon>新建计划</button>
      </template>
    </PageHeader>

    <div class="page-content">
      <section class="surface schedule-toolbar">
        <div class="search-field">
          <el-icon><Search /></el-icon>
          <label class="visually-hidden" for="schedule-query">搜索定时任务</label>
          <input id="schedule-query" v-model="query" name="scheduleQuery" type="search" autocomplete="off" placeholder="搜索计划、工作流或频率…">
        </div>
        <label>启用状态
          <select v-model="status" name="scheduleStatus">
            <option value="all">全部计划</option><option value="enabled">仅已启用</option><option value="disabled">仅已停用</option>
          </select>
        </label>
        <span class="schedule-total">{{ filteredSchedules.length }} 个计划</span>
      </section>

      <LoadingState v-if="loading" />
      <section v-else class="surface schedule-surface">
        <header class="surface-header">
          <div><p class="section-kicker">执行计划</p><h2>任务计划</h2><span>启停操作立即生效；编辑内容保存后生效。</span></div>
        </header>
        <EmptyState
          v-if="!filteredSchedules.length"
          :title="schedules.length ? '没有匹配的计划' : '还没有定时任务'"
          :description="schedules.length ? '调整搜索或状态筛选条件。' : '创建计划，让工作流按固定时间自动执行。'"
        >
          <button v-if="!schedules.length" class="button button-primary" type="button" @click="createSchedule">创建计划</button>
        </EmptyState>
        <div v-else class="responsive-table">
          <table class="schedule-table">
            <thead><tr><th>计划</th><th>运行策略</th><th>执行频率</th><th>下次执行</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="schedule in filteredSchedules" :key="schedule.id">
                <td>
                  <span class="schedule-title-icon" aria-hidden="true"><el-icon><Calendar /></el-icon></span>
                  <span><strong class="table-primary">{{ chainLabels[schedule.chainId] ?? schedule.chainId }}</strong><small>计划 #{{ schedule.id }}</small></span>
                </td>
                <td><strong>{{ schedule.mode === 'rerun' ? '重跑' : '全量运行' }}</strong><small>{{ schedule.rerunType || '标准执行' }}</small></td>
                <td><strong>{{ scheduleFrequency(schedule) }}</strong><small>{{ schedule.frequency === 'ONCE' ? '一次性计划' : '循环计划' }}</small></td>
                <td><strong>{{ formatDateTime(schedule.nextTriggerAt) }}</strong><small v-if="schedule.lastTriggeredAt">上次 {{ formatDateTime(schedule.lastTriggeredAt) }}</small></td>
                <td>
                  <button
                    class="toggle-switch"
                    :class="{ enabled: schedule.enabled }"
                    type="button"
                    role="switch"
                    :aria-checked="schedule.enabled"
                    :aria-label="`${chainLabels[schedule.chainId] ?? schedule.chainId} #${schedule.id}，当前${schedule.enabled ? '已启用，点击停用' : '已停用，点击启用'}`"
                    @click="toggle(schedule)"
                  >
                    <span aria-hidden="true"><i></i></span><b>{{ schedule.enabled ? '已启用' : '已停用' }}</b>
                  </button>
                </td>
                <td>
                  <div class="table-actions">
                    <button class="text-button" type="button" @click="fillFromSchedule(schedule, false)"><el-icon><EditPen /></el-icon>编辑</button>
                    <button class="text-button" type="button" @click="fillFromSchedule(schedule, true)"><el-icon><CopyDocument /></el-icon>复制</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <button
      v-if="drawerOpen"
      class="drawer-backdrop"
      type="button"
      aria-label="关闭定时任务抽屉"
      @click="drawerOpen = false"
    ></button>
    <section
      class="schedule-drawer"
      :class="{ open: drawerOpen }"
      role="dialog"
      aria-modal="true"
      aria-labelledby="schedule-drawer-title"
      :aria-hidden="!drawerOpen"
    >
      <form @submit.prevent="submit">
        <header class="drawer-header">
          <div><p class="section-kicker">任务计划</p><h2 id="schedule-drawer-title">{{ editingId === null ? '新建计划' : `编辑计划 #${editingId}` }}</h2><span>配置工作流、执行时间和运行参数。</span></div>
          <button class="icon-action" type="button" aria-label="关闭定时任务抽屉" @click="drawerOpen = false"><el-icon><Close /></el-icon></button>
        </header>

        <section class="drawer-section">
          <header><span>01</span><div><h3>运行设置</h3><p>选择工作流与执行模式。</p></div></header>
          <div class="form-grid">
            <label class="is-wide">工作流
              <select v-model="form.chainId" name="chainId"><option v-for="chain in chains" :key="chain" :value="chain">{{ chainLabels[chain] ?? chain }}</option></select>
            </label>
            <label>模式<select v-model="form.mode" name="mode"><option value="full">全量运行</option><option value="rerun">重跑</option></select></label>
            <label v-if="form.mode === 'rerun'">重跑类型
              <select v-model="form.rerunType" name="rerunType"><option v-for="item in rerunTypes" :key="item.value" :value="item.value">{{ item.label }}</option></select>
            </label>
            <label v-if="form.mode === 'rerun' && selectedRerun?.requiresId">重跑编号
              <input v-model="form.rerunId" name="rerunId" autocomplete="off" :placeholder="selectedRerun.idPlaceholder + '…'">
            </label>
            <label>运行日期<input v-model="form.runDate" name="runDate" type="date"></label>
          </div>
        </section>

        <section class="drawer-section">
          <header><span>02</span><div><h3>执行频率</h3><p>设置触发规则与启用状态。</p></div></header>
          <div class="form-grid">
            <label>频率<select v-model="form.frequency" name="frequency"><option value="daily">每天</option><option value="weekly">每周</option><option value="once">一次性</option></select></label>
            <label v-if="form.frequency === 'weekly'">星期
              <select v-model="form.dayOfWeek" name="dayOfWeek">
                <option v-for="(day, index) in ['周一','周二','周三','周四','周五','周六','周日']" :key="day" :value="index + 1">{{ day }}</option>
              </select>
            </label>
            <label v-if="form.frequency !== 'once'">执行时间<input v-model="form.runTime" name="runTime" type="time" required></label>
            <label v-else>执行日期时间<input v-model="form.runAt" name="runAt" type="datetime-local" required></label>
            <label class="checkbox-card is-wide">
              <input v-model="form.enabled" name="enabled" type="checkbox"><span><strong>保存后立即启用</strong><small>关闭时只保存计划，不会自动触发。</small></span>
            </label>
          </div>
        </section>

        <section class="drawer-section">
          <header><span>03</span><div><h3>链路配置</h3><p>{{ chainDescription }}</p></div></header>
          <DynamicConfigForm v-model="config" :chain-id="form.chainId" :disabled="saving" />
        </section>

        <footer class="drawer-footer">
          <button class="button button-quiet" type="button" @click="drawerOpen = false">取消</button>
          <button class="button button-primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存计划' }}</button>
        </footer>
      </form>
    </section>
  </div>
</template>
