import axios from 'axios'

// 统一走 /api 前缀，开发环境由 vite 代理转发到后端 8080
const http = axios.create({
  baseURL: '/api/pbccrc',
  timeout: 30000,
})

// 后端开启 pbccrc.security.enabled 后所有接口都校验 X-Auth-Token，
// 这里统一从 localStorage 取，避免每个调用点自己拼请求头（漏一个就 401）
const TOKEN_KEY = 'pbccrc_token'

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers['X-Auth-Token'] = token
  }
  return config
})

// 响应拦截：后端统一 Result{code,message,data}，code=0 为成功
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code !== 'undefined' && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (err) => {
    const msg = err.response?.data?.message || err.message || '网络异常'
    return Promise.reject(new Error(msg))
  },
)

export function pageReports(params) {
  return http.post('/admin/reports/page', params)
}

export function getReportHtml(id) {
  return http.get(`/admin/reports/${id}/html`)
}

export function submitReport(payload) {
  return http.post('/reports', payload)
}
