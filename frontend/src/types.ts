export type RunState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface WorkflowRun {
  id: number
  chainId: string
  mode: string
  rerunType: string | null
  rerunId: string | null
  runDate: string | null
  state: RunState
  phase: string
  configPath: string | null
  failureMessage: string | null
  outputPath: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface RunTask {
  runId: number
  taskKey: string
  taskName: string
  state: string
  phase: string
  statusPath: string | null
  errorMessage: string | null
  updatedAt: string
}

export interface RunEvent {
  id: number
  runId: number
  eventType: string
  message: string
  createdAt: string
}

export interface RunSummary {
  totalTasks: number
  succeededTasks: number
  failedTasks: number
  durationSeconds: number
  failureMessage: string | null
  failedTaskKey: string | null
  lastErrorMessage: string | null
}

export interface RerunAction {
  visible: boolean
  available: boolean
  rerunType: string
  rerunId: string
  reason: string
}

export interface RunSnapshot {
  run: WorkflowRun
  summary: RunSummary
  tasks: RunTask[]
  events: RunEvent[]
  rerunAction: RerunAction
}

export interface RunConfigResponse {
  sourceRunId: number
  chainId: string
  mode: string
  rerunType: string | null
  rerunId: string | null
  runDate: string | null
  config: Record<string, unknown>
}

export interface WorkflowSchedule {
  id: number
  chainId: string
  mode: string
  rerunType: string | null
  rerunId: string | null
  runDate: string | null
  config: Record<string, unknown>
  frequency: 'DAILY' | 'WEEKLY' | 'ONCE'
  dayOfWeek: number | null
  runTime: string | null
  runAt: string | null
  enabled: boolean
  lastTriggeredAt: string | null
  nextTriggerAt: string | null
  createdAt: string
  updatedAt: string
}

export interface SchedulePayload {
  chainId: string
  mode: string
  rerunType: string | null
  rerunId: string | null
  runDate: string | null
  config: Record<string, unknown>
  frequency: string
  dayOfWeek: number | null
  runTime: string | null
  runAt: string | null
  enabled: boolean
}

export interface PathPreflight {
  accessible: boolean
  directory: boolean
  mavenProject: boolean
  message: string
}
