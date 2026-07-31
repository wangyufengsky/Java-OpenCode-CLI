import { createApp } from 'vue'
import { ElIcon } from 'element-plus'
import 'element-plus/theme-chalk/el-icon.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import App from '@/App.vue'
import router from '@/router'
import '@/styles/tokens.css'
import '@/styles/base.css'

createApp(App).component('ElIcon', ElIcon).use(router).mount('#app')
