import { createRouter, createWebHistory } from 'vue-router'
import ReportList from '../views/ReportList.vue'
import ReportImport from '../views/ReportImport.vue'

const routes = [
  { path: '/', redirect: '/reports' },
  { path: '/reports', name: 'reports', component: ReportList, meta: { title: '报告列表' } },
  { path: '/import', name: 'import', component: ReportImport, meta: { title: '报告导入' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
