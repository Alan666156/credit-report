<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="logo">CREDIT<span>//</span>REPORT</div>
        <div class="sub">征信报告管理平台 v1.0</div>
      </div>
      <nav class="nav">
        <router-link to="/reports" class="nav-item" active-class="active">
          <span class="icon">▤</span><span>报告列表</span>
        </router-link>
        <router-link to="/import" class="nav-item" active-class="active">
          <span class="icon">⇧</span><span>报告导入</span>
        </router-link>
      </nav>
      <div class="foot">
        <div><span class="dot"></span>SYSTEM ONLINE</div>
        <div>SECURE CHANNEL // PBCCRC</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">SYS / <b>{{ $route.meta.title }}</b></div>
        <div class="status"><span class="pulse"></span>NODE:8080 · CONNECTED</div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup></script>
