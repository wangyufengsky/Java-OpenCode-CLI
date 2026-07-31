<script setup lang="ts">
import { computed, reactive } from 'vue'
import { CircleCheck, FolderOpened } from '@element-plus/icons-vue'
import { inspectPath } from '@/api'

const props = defineProps<{
  chainId: string
  modelValue: Record<string, unknown>
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>] }>()
const preflight = reactive<Record<string, { loading: boolean; ok: boolean; message: string }>>({})

const definition = computed(() => window.ConsoleCommon?.chainConfigDefinitions[props.chainId])
const groups = computed(() => {
  const groupDefinitions = window.ConsoleCommon?.groupDefinitions ?? []
  return groupDefinitions.map((group) => ({
    ...group,
    fields: definition.value?.fields.filter((field) => field.group === group.group) ?? []
  })).filter((group) => group.fields.length)
})

function update(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function inputValue(key: string): string | number {
  const value = props.modelValue[key]
  if (Array.isArray(value)) return value.join('\n')
  if (typeof value === 'number') return value
  return value == null ? '' : String(value)
}

function updateText(field: ConsoleFieldDefinition, event: Event) {
  const element = event.target as HTMLInputElement | HTMLTextAreaElement
  let value: unknown = element.value
  if (field.type === 'number') value = element.value === '' ? null : Number(element.value)
  if (field.type === 'list') value = element.value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
  update(field.key, value)
}

async function runPreflight(field: ConsoleFieldDefinition) {
  const value = String(props.modelValue[field.key] ?? '').trim()
  if (!value || field.type !== 'path') return
  preflight[field.key] = { loading: true, ok: false, message: '正在检查路径…' }
  try {
    const result = await inspectPath(value)
    preflight[field.key] = {
      loading: false,
      ok: result.accessible,
      message: result.mavenProject ? '路径可访问，已识别 Maven 项目' : result.message
    }
  } catch (error) {
    preflight[field.key] = { loading: false, ok: false, message: (error as Error).message }
  }
}
</script>

<template>
  <div class="config-groups">
    <section v-for="group in groups" :key="group.group" class="config-group">
      <header>
        <span class="config-group-icon" aria-hidden="true">
          <el-icon><FolderOpened /></el-icon>
        </span>
        <div><h3>{{ group.label }}</h3><p>{{ group.description }}</p></div>
      </header>
      <div class="config-field-grid">
        <label
          v-for="field in group.fields"
          :key="field.key"
          class="config-field"
          :class="{ 'is-wide': field.type === 'textarea' || field.type === 'list' }"
          :for="`config-${field.key}`"
        >
          <span class="field-label">
            {{ field.label }}<b v-if="field.required" aria-label="必填">*</b>
          </span>
          <span v-if="field.type === 'checkbox'" class="switch-field">
            <input
              :id="`config-${field.key}`"
              type="checkbox"
              :name="field.key"
              :checked="Boolean(modelValue[field.key])"
              :disabled="disabled"
              @change="update(field.key, ($event.target as HTMLInputElement).checked)"
            >
            <span>启用</span>
          </span>
          <textarea
            v-else-if="field.type === 'textarea' || field.type === 'list'"
            :id="`config-${field.key}`"
            :name="field.key"
            :value="inputValue(field.key)"
            :required="field.required"
            :disabled="disabled"
            rows="3"
            :placeholder="field.type === 'list' ? '每行填写一项…' : '填写详细内容…'"
            @input="updateText(field, $event)"
          ></textarea>
          <span v-else class="input-with-icon">
            <el-icon v-if="field.type === 'path'"><FolderOpened /></el-icon>
            <input
              :id="`config-${field.key}`"
              :name="field.key"
              :type="field.type === 'number' ? 'number' : 'text'"
              :inputmode="field.type === 'number' ? 'numeric' : undefined"
              :value="inputValue(field.key)"
              :required="field.required"
              :disabled="disabled"
              autocomplete="off"
              :placeholder="field.type === 'path' ? '输入本地绝对路径…' : `填写${field.label}…`"
              @input="updateText(field, $event)"
              @blur="runPreflight(field)"
            >
          </span>
          <small>{{ field.description }}</small>
          <span
            v-if="preflight[field.key]"
            class="path-status"
            :class="{ success: preflight[field.key].ok, loading: preflight[field.key].loading }"
            aria-live="polite"
          >
            <el-icon v-if="preflight[field.key].ok"><CircleCheck /></el-icon>
            {{ preflight[field.key].message }}
          </span>
        </label>
      </div>
    </section>
  </div>
</template>
