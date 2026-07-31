import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '运行概览' } },
    { path: '/runs/new', name: 'new-run', component: () => import('@/views/RunFormView.vue'), meta: { title: '新建运行' } },
    { path: '/history', name: 'history', component: () => import('@/views/HistoryView.vue'), meta: { title: '运行历史' } },
    { path: '/runs/:id(\\d+)', name: 'run-detail', component: () => import('@/views/RunDetailView.vue'), meta: { title: '运行详情' } },
    { path: '/schedules', name: 'schedules', component: () => import('@/views/SchedulesView.vue'), meta: { title: '定时任务' } }
  ],
  scrollBehavior: () => ({ top: 0 })
})
