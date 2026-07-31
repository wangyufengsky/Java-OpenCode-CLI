import { describe, expect, it } from 'vitest'
import { chainLabels, formatDuration, scheduleFrequency } from '@/formatters'

describe('console formatters', () => {
  it('uses stable Chinese workflow labels', () => {
    expect(chainLabels['project-unit-test-generation']).toBe('单元测试生成')
  })

  it('formats completed run duration', () => {
    expect(formatDuration({
      startedAt: '2026-07-31T01:00:00Z',
      finishedAt: '2026-07-31T01:01:05Z'
    } as never)).toBe('1 分 5 秒')
  })

  it('formats weekly schedules', () => {
    expect(scheduleFrequency({
      frequency: 'WEEKLY',
      dayOfWeek: 5,
      runTime: '07:30:00'
    } as never)).toBe('每周五 07:30')
  })
})
