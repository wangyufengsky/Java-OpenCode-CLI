<script setup lang="ts">
import { Document } from '@element-plus/icons-vue'

defineProps<{ title: string; description: string }>()
</script>

<template>
  <div class="empty-state" role="status">
    <span class="empty-icon" aria-hidden="true"><el-icon><Document /></el-icon></span>
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
    <slot />
  </div>
</template>
