import type { WorkflowRun, WorkflowSchedule } from '@/types'

export const chainLabels: Record<string, string> = {
  'git-code-contribution-report': '代码贡献报告',
  'smartesb-rewrite-code-review': 'SmartESB 改造评审',
  'smartesb-code-reader': 'SmartESB 代码阅读',
  'weekly-engineering-report': '研发周报',
  'project-unit-test-generation': '单元测试生成',
  'mybatis-sql-review': 'MyBatis SQL 审查'
}

export const stateLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已成功',
  FAILED: '已失败'
}

export const phaseLabels: Record<string, string> = {
  started: '已开始',
  submitted: '已提交',
  queued: '排队中',
  running: '运行中',
  idle: '空闲',
  complete: '已完成',
  completed: '已完成',
  timeout: '已超时',
  failed: '已失败',
  session_failed: '会话失败，正在重试',
  execution: '执行中',
  validation_failed_final: '最终校验失败'
}

export const eventLabels: Record<string, string> = {
  QUEUED: '已排队',
  STARTED: '已开始',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  TASK_GROUP_STARTED: '任务组已开始',
  TASK_GROUP_SUCCEEDED: '任务组已完成',
  TASK_QUEUED: '任务已排队',
  TASK_RUNNING: '任务运行中',
  TASK_SUCCEEDED: '任务已成功',
  TASK_FAILED: '任务已失败',
  SESSION_FAILED: '会话已失败'
}

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
})

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

export function formatDuration(run: WorkflowRun): string {
  if (!run.startedAt) return '未开始'
  const end = run.finishedAt ? new Date(run.finishedAt).getTime() : Date.now()
  const start = new Date(run.startedAt).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end)) return '—'
  const seconds = Math.max(0, Math.floor((end - start) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${seconds % 60} 秒`
}

export function scheduleFrequency(schedule: WorkflowSchedule): string {
  if (schedule.frequency === 'DAILY') return `每天 ${schedule.runTime?.slice(0, 5) ?? ''}`
  if (schedule.frequency === 'WEEKLY') {
    return `每周${'一二三四五六日'[Math.max(0, (schedule.dayOfWeek ?? 1) - 1)]} ${schedule.runTime?.slice(0, 5) ?? ''}`
  }
  return schedule.runAt ? `一次性 ${schedule.runAt.replace('T', ' ').slice(0, 16)}` : '一次性'
}
