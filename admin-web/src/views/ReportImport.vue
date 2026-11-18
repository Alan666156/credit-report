<template>
  <div>
    <div class="page-head">
      <h1>报告导入</h1>
      <p>IMPORT REPORT // 上传用户 HTML 征信报告，调用后端解析并入库</p>
    </div>

    <div class="import-grid">
      <!-- 输入表单 -->
      <div class="panel">
        <div class="panel-title">用户信息 / SUBJECT</div>
        <div class="field" style="margin-bottom:16px">
          <label>用户姓名 *</label>
          <input v-model.trim="form.customerName" placeholder="请输入用户姓名" />
        </div>
        <div class="field" style="margin-bottom:16px">
          <label>身份证号 *</label>
          <input v-model.trim="form.customerIdCard" placeholder="请输入身份证号码" maxlength="18" />
        </div>
        <div class="field">
          <label>业务号（可选）</label>
          <input v-model.trim="form.appNo" placeholder="appNo / 业务流水号" />
        </div>

        <div class="panel-title" style="margin-top:24px">报告文件 / FILE</div>
        <div
          class="dropzone"
          :class="{ drag: dragging }"
          @click="triggerFile"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <div class="dz-icon">⇧</div>
          <div class="dz-text">点击选择或拖拽 HTML 报告到此处</div>
          <div class="dz-file" v-if="file">{{ '▣ ' + file.name + '  (' + formatSize(file.size) + ')' }}</div>
          <div class="dz-text" v-else>支持 .html / .htm · 最大 10MB</div>
        </div>
        <input ref="fileInput" type="file" accept=".html,.htm,text/html" style="display:none" @change="onFileChange" />

        <div class="hint" style="margin-top:16px">
          <b>NOTE //</b> 提交后先同步落原文，再触发异步解析。<br />
          7 天内同一「姓名+身份证」不允许重复提交。
        </div>

        <button
          class="btn magenta"
          style="margin-top:20px; width:100%; padding:12px"
          :disabled="submitting"
          @click="submit"
        >{{ submitting ? '提交中…' : '⇧ 上传并解析入库' }}</button>
        <div class="progress" v-if="submitting"><div class="bar"></div></div>
      </div>

      <!-- 结果 -->
      <div class="panel">
        <div class="panel-title">执行结果 / RESULT</div>
        <div v-if="!result && !error" class="state">// 等待提交任务…</div>
        <div v-else-if="error" class="result-box err">
          <div><span class="k">STATUS :</span> <span class="v">✕ FAILED</span></div>
          <div><span class="k">MESSAGE:</span> <span class="v">{{ error }}</span></div>
        </div>
        <div v-else class="result-box ok">
          <div><span class="k">STATUS :</span> <span class="v">✓ SUBMITTED</span></div>
          <div><span class="k">REPORT ID:</span> <span class="v">#{{ result.data }}</span></div>
          <div><span class="k">SUBJECT :</span> <span class="v">{{ form.customerName }}</span></div>
          <div><span class="k">STATE  :</span> <span class="v">PENDING → 解析中</span></div>
        </div>

        <div class="panel-title" style="margin-top:24px">操作说明 / GUIDE</div>
        <div class="hint">
          1. 填写用户<b>姓名</b>与<b>身份证号</b>；<br />
          2. 选择符合格式的 <b>HTML</b> 征信报告文件；<br />
          3. 点击「上传并解析入库」；<br />
          4. 后端解析完成后，可在「报告列表」查看状态与原始报告。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { submitReport } from '../api'

const form = ref({ customerName: '', customerIdCard: '', appNo: '' })
const file = ref(null)
const fileInput = ref(null)
const dragging = ref(false)
const submitting = ref(false)
const result = ref(null)
const error = ref('')

function triggerFile() { fileInput.value?.click() }
function formatSize(n) { return n > 1024 ? (n / 1024).toFixed(1) + ' KB' : n + ' B' }

function onFileChange(e) {
  const f = e.target.files?.[0]
  if (f) setFile(f)
}
function onDrop(e) {
  const f = e.dataTransfer.files?.[0]
  dragging.value = false
  if (f) setFile(f)
}
function setFile(f) {
  const name = (f.name || '').toLowerCase()
  if (!name.endsWith('.html') && !name.endsWith('.htm')) {
    error.value = '仅支持 .html / .htm 文件'
    result.value = null
    return
  }
  if (f.size > 10 * 1024 * 1024) {
    error.value = '文件不能超过 10MB'
    result.value = null
    return
  }
  file.value = f
  error.value = ''
}

function readFile(f) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsText(f, 'UTF-8')
  })
}

async function submit() {
  error.value = ''
  result.value = null
  if (!form.value.customerName) { error.value = '请填写用户姓名'; return }
  if (!form.value.customerIdCard) { error.value = '请填写身份证号'; return }
  if (!file.value) { error.value = '请选择 HTML 报告文件'; return }

  submitting.value = true
  try {
    const htmlContent = await readFile(file.value)
    const res = await submitReport({
      customerName: form.value.customerName,
      customerIdCard: form.value.customerIdCard,
      appNo: form.value.appNo || undefined,
      htmlContent,
    })
    result.value = res
  } catch (e) {
    error.value = e.message || '提交失败'
  } finally {
    submitting.value = false
  }
}
</script>
