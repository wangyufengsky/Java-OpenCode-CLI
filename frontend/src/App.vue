<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Calendar,
  Clock,
  DataAnalysis,
  Menu,
  Plus,
  Setting,
  SwitchButton
} from '@element-plus/icons-vue'

const route = useRoute()
const mobileOpen = ref(false)
const title = computed(() => String(route.meta.title ?? '任务控制台'))

watch(() => route.fullPath, () => {
  mobileOpen.value = false
  document.title = `${title.value} · AgentBridge`
}, { immediate: true })

const navigation = [
  { to: '/', label: '运行概览', caption: '任务与系统状态', icon: DataAnalysis },
  { to: '/runs/new', label: '新建运行', caption: '配置并提交任务', icon: Plus },
  { to: '/history', label: '运行历史', caption: '筛选与复用记录', icon: Clock },
  { to: '/schedules', label: '定时任务', caption: '自动化执行计划', icon: Calendar }
]
</script>

<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <div class="console-shell">
    <button
      class="mobile-menu-button"
      type="button"
      :aria-expanded="mobileOpen"
      aria-label="打开主导航"
      @click="mobileOpen = !mobileOpen"
    >
      <el-icon><Menu /></el-icon>
    </button>

    <aside class="app-sidebar" :class="{ 'is-open': mobileOpen }">
      <RouterLink class="brand" to="/" aria-label="AgentBridge 任务控制台首页">
        <span class="brand-mark" aria-hidden="true">
          <span></span><span></span><span></span>
        </span>
        <span>
          <strong>AgentBridge</strong>
          <small>任务控制台</small>
        </span>
      </RouterLink>

      <p class="nav-section-label">工作空间</p>
      <nav class="side-navigation" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          :class="{ active: route.path === item.to || (item.to === '/history' && route.name === 'run-detail') }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></span>
        </RouterLink>
      </nav>

      <div class="sidebar-spacer"></div>
      <div class="local-status">
        <span class="status-dot" aria-hidden="true"></span>
        <span><strong>本地服务</strong><small>数据仅保存在当前环境</small></span>
      </div>
      <div class="sidebar-footer">
        <el-icon><Setting /></el-icon>
        <span>AgentBridge Runtime</span>
        <el-icon class="power-icon"><SwitchButton /></el-icon>
      </div>
    </aside>

    <button
      v-if="mobileOpen"
      class="sidebar-backdrop"
      type="button"
      aria-label="关闭主导航"
      @click="mobileOpen = false"
    ></button>

    <main id="main-content" class="app-main" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>
