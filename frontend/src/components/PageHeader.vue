<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'

defineProps<{
  eyebrow: string
  title: string
  description: string
  refreshing?: boolean
  refreshable?: boolean
}>()

const emit = defineEmits<{ refresh: [] }>()
</script>

<template>
  <header class="page-header">
    <div class="page-header-copy">
      <p class="page-eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
      <button
        v-if="refreshable"
        class="icon-action"
        type="button"
        aria-label="刷新页面数据"
        :disabled="refreshing"
        @click="emit('refresh')"
      >
        <el-icon :class="{ rotating: refreshing }"><Refresh /></el-icon>
      </button>
    </div>
  </header>
</template>
