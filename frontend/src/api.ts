import type {
  PathPreflight,
  RunConfigResponse,
  RunSnapshot,
  SchedulePayload,
  WorkflowRun,
  WorkflowSchedule
} from '@/types'

async function readJson<T>(response: Response): Promise<T> {
  let body: unknown = {}
  try {
    body = await response.json()
  } catch {
    body = {}
  }
  if (!response.ok) {
    const message = typeof body === 'object' && body && 'error' in body
      ? String((body as { error: unknown }).error)
      : `请求失败（${response.status}）`
    throw new Error(message)
  }
  return body as T
}

export async function getChains(): Promise<string[]> {
  const response = await fetch('/api/chains')
  const body = await readJson<{ chains: string[] }>(response)
  return body.chains
}

export async function getDefaults(chainId: string): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/chains/${encodeURIComponent(chainId)}/defaults`)
  const body = await readJson<{ defaults: Record<string, unknown> }>(response)
  return body.defaults
}

export async function getRuns(): Promise<WorkflowRun[]> {
  return readJson(await fetch('/api/runs'))
}

export async function getRunSnapshot(id: number, afterEventId = 0): Promise<RunSnapshot> {
  return readJson(await fetch(`/api/runs/${id}/snapshot?afterEventId=${Math.max(0, afterEventId)}`))
}

export async function getRunConfig(id: number): Promise<RunConfigResponse> {
  return readJson(await fetch(`/api/runs/${id}/config`))
}

export async function createRun(payload: Record<string, unknown>): Promise<number> {
  const body = await readJson<{ id: number }>(await fetch('/api/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }))
  return body.id
}

export async function rerun(id: number): Promise<number> {
  const body = await readJson<{ id: number }>(await fetch(`/api/runs/${id}/rerun`, { method: 'POST' }))
  return body.id
}

export async function deleteRun(id: number): Promise<void> {
  await readJson(await fetch(`/api/runs/${id}`, { method: 'DELETE' }))
}

export async function clearRuns(): Promise<number> {
  const body = await readJson<{ deleted: number }>(await fetch('/api/runs', { method: 'DELETE' }))
  return body.deleted
}

export async function getSchedules(): Promise<WorkflowSchedule[]> {
  return readJson(await fetch('/api/schedules'))
}

export async function saveSchedule(id: number | null, payload: SchedulePayload): Promise<void> {
  const url = id === null ? '/api/schedules' : `/api/schedules/${id}`
  await readJson(await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }))
}

export async function setScheduleEnabled(id: number, enabled: boolean): Promise<WorkflowSchedule> {
  return readJson(await fetch(`/api/schedules/${id}/enabled`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled })
  }))
}

export async function inspectPath(path: string): Promise<PathPreflight> {
  return readJson(await fetch(`/api/path-preflight?path=${encodeURIComponent(path)}`))
}
