<script setup lang="ts">
import { computed } from 'vue'
import { stateLabels } from '@/formatters'

const props = defineProps<{ state: string }>()
const label = computed(() => stateLabels[props.state] ?? props.state ?? '未知')
</script>

<template>
  <span class="status-badge" :data-state="state">
    <span class="status-badge-dot" aria-hidden="true"></span>{{ label }}
  </span>
</template>
