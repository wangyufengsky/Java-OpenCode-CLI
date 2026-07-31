import { describe, expect, it } from 'vitest'
import {
  chainConfigDefinitions,
  groupDefinitions,
  rerunTypeDefinitions
} from './consoleConfig'

describe('console configuration catalog', () => {
  it('contains every workflow used by the console', () => {
    expect(Object.keys(chainConfigDefinitions)).toEqual([
      'git-code-contribution-report',
      'smartesb-rewrite-code-review',
      'smartesb-code-reader',
      'weekly-engineering-report',
      'project-unit-test-generation',
      'mybatis-sql-review'
    ])
    expect(groupDefinitions.map((group) => group.group)).toEqual([
      'project',
      'scope',
      'validation',
      'agentbridge'
    ])
  })

  it('keeps the MyBatis fields and rerun contract in the Vue bundle', () => {
    const fields = chainConfigDefinitions['mybatis-sql-review'].fields
    expect(fields.map((field) => field.key)).toEqual(expect.arrayContaining([
      'project.id',
      'project.repo',
      'source.paths',
      'database.connection-name',
      'database.statement-timeout-seconds',
      'agentbridge.mcp-url'
    ]))
    expect(rerunTypeDefinitions['mybatis-sql-review']).toEqual([
      expect.objectContaining({ value: 'sql', requiresId: true }),
      expect.objectContaining({ value: 'xml', requiresId: true }),
      expect.objectContaining({ value: 'index', requiresId: false })
    ])
  })

  it('marks required fields explicitly for form validation', () => {
    const definition = chainConfigDefinitions['git-code-contribution-report']
    expect(definition.fields.find((field) => field.key === 'project.id')?.required).toBe(true)
    expect(definition.fields.find((field) => field.key === 'git.author-map')?.required).toBe(false)
  })
})
