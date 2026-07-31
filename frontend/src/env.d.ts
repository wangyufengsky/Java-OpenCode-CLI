/// <reference types="vite/client" />

interface ConsoleFieldDefinition {
  key: string
  label: string
  description: string
  type: 'text' | 'number' | 'checkbox' | 'textarea' | 'list' | 'path'
  group: string
  required: boolean
  summary: boolean
}

interface ConsoleChainDefinition {
  label: string
  description: string
  fields: ConsoleFieldDefinition[]
}

interface ConsoleRerunType {
  value: string
  label: string
  requiresId: boolean
  idPlaceholder: string
}

interface Window {
  ConsoleCommon?: {
    chainConfigDefinitions: Record<string, ConsoleChainDefinition>
    groupDefinitions: Array<{ group: string; label: string; description: string }>
    rerunTypeDefinitions: Record<string, ConsoleRerunType[]>
  }
}
