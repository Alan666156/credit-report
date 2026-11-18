<template>
  <div>
    <div class="page-head">
      <h1>报告列表</h1>
      <p>QUERY REPORT ARCHIVE // 按条件检索已入库的用户征信报告</p>
    </div>

    <!-- 查询条件 -->
    <div class="panel">
      <div class="panel-title">查询条件 / FILTERS</div>
      <div class="filter-row">
        <div class="field">
          <label>用户名</label>
          <input v-model="filters.customerName" placeholder="客户姓名（模糊）" @keyup.enter="search" />
        </div>
        <div class="field">
          <label>姓名</label>
          <input v-model="filters.name" placeholder="报告姓名（模糊）" @keyup.enter="search" />
        </div>
        <div class="field">
          <label>创建时间</label>
          <div class="range">
            <input type="date" v-model="filters.dateStart" />
            <span class="cell-dim">→</span>
            <input type="date" v-model="filters.dateEnd" />
          </div>
        </div>
        <div class="field">
          <label>&nbsp;</label>
          <div class="range">
            <button class="btn" @click="search">▶ 查询</button>
            <button class="btn ghost" @click="reset">重置</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 列表 -->
    <div class="panel">
      <div class="panel-title">报告档案 / ARCHIVE · 共 {{ total }} 条</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>客户姓名</th>
              <th>证件号</th>
              <th>报告姓名</th>
              <th>报告编号</th>
              <th>状态</th>
              <th>上传时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="8" class="state">正在读取数据<span class="glitch">_</span></td>
            </tr>
            <tr v-else-if="rows.length === 0">
              <td colspan="8" class="state">// 未查询到匹配的报告记录</td>
            </tr>
            <tr v-else v-for="row in rows" :key="row.id">
              <td class="cell-id">#{{ row.id }}</td>
              <td>{{ row.customerName || '—' }}</td>
              <td class="cell-dim">{{ maskId(row.customerIdCard) }}</td>
              <td>{{ row.name || '—' }}</td>
              <td class="cell-dim">{{ row.reportNo || '—' }}</td>
              <td><span :class="['badge', statusClass(row.status)]">{{ statusText(row.status) }}</span></td>
              <td class="cell-dim">{{ formatTime(row.createTime) }}</td>
              <td>
                <button class="btn sm magenta" @click="openReport(row)">查看HTML报告</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pagination">
        <div>第 {{ pageNum }} / {{ totalPages }} 页 · 每页 {{ pageSize }} 条</div>
        <div class="pages">
          <button class="page-btn" :disabled="pageNum <= 1" @click="go(pageNum - 1)">‹</button>
          <button
            v-for="p in visiblePages"
            :key="p"
            class="page-btn"
            :class="{ active: p === pageNum }"
            @click="go(p)"
          >{{ p }}</button>
          <button class="page-btn" :disabled="pageNum >= totalPages" @click="go(pageNum + 1)">›</button>
        </div>
      </div>
    </div>

    <!-- HTML 报告查看模态框 -->
    <div class="modal-mask" v-if="modalOpen" @click.self="modalOpen = false">
      <div class="modal">
        <div class="modal-head">
          <span>原始报告 // REPORT #{{ current?.id }} · {{ current?.customerName }}</span>
          <button class="close" @click="modalOpen = false">×</button>
        </div>
        <div class="modal-body">
          <div v-if="htmlLoading" class="state">正在载入报告正文<span class="glitch">_</span></div>
          <iframe v-else title="report" sandbox="allow-same-origin" :srcdoc="htmlContent"></iframe>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { pageReports, getReportHtml } from '../api'

const filters = ref({ customerName: '', name: '', dateStart: '', dateEnd: '' })
const rows = ref([])
const loading = ref(false)
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const modalOpen = ref(false)
const htmlLoading = ref(false)
const htmlContent = ref('')
const current = ref(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const visiblePages = computed(() => {
  const tp = totalPages.value
  const cur = pageNum.value
  const span = 2
  let start = Math.max(1, cur - span)
  let end = Math.min(tp, cur + span)
  if (cur <= span) end = Math.min(tp, span * 2 + 1)
  if (cur >= tp - span) start = Math.max(1, tp - span * 2)
  const out = []
  for (let i = start; i <= end; i++) out.push(i)
  return out
})

function maskId(id) {
  if (!id) return '—'
  const s = String(id)
  if (s.length <= 8) return s
  return s.slice(0, 4) + '********' + s.slice(-4)
}

function formatTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

function statusText(s) {
  return ({ SUCCESS: '成功', PENDING: '待解析', PARSING: '解析中', FAILED: '失败' })[s] || s || '未知'
}
function statusClass(s) {
  return ({ SUCCESS: 'success', PENDING: 'pending', PARSING: 'parsing', FAILED: 'failed' })[s] || 'unknown'
}

function toDateTimeEnd(d) {
  return d ? `${d} 23:59:59` : ''
}

async function load() {
  loading.value = true
  try {
    const res = await pageReports({
      customerName: filters.value.customerName || '',
      name: filters.value.name || '',
      createTimeStart: filters.value.dateStart ? `${filters.value.dateStart} 00:00:00` : '',
      createTimeEnd: toDateTimeEnd(filters.value.dateEnd),
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    rows.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    window.alert('查询失败：' + e.message)
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  load()
}
function reset() {
  filters.value = { customerName: '', name: '', dateStart: '', dateEnd: '' }
  search()
}
function go(p) {
  if (p < 1 || p > totalPages.value) return
  pageNum.value = p
  load()
}

async function openReport(row) {
  current.value = row
  modalOpen.value = true
  htmlLoading.value = true
  htmlContent.value = ''
  try {
    const res = await getReportHtml(row.id)
    htmlContent.value = res.data || '<p style="padding:24px;color:#666">报告正文为空</p>'
  } catch (e) {
    htmlContent.value = `<p style="padding:24px;color:#c00">载入失败：${e.message}</p>`
  } finally {
    htmlLoading.value = false
  }
}

onMounted(load)
</script>
