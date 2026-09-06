<script setup lang="ts">
import { getState, toggleState } from "./utils/state";
import { clearModuleLog, loadModuleLog } from "./utils/log";

const err = ref("")
const qqStatus = ref(false);
const logPath = ref("");
const logText = ref("打开后自动读取模块日志");
const logLoading = ref(false);
onBeforeMount(async () => {
  qqStatus.value = await getState();
  await refreshLog();
})
const loading = ref(false);
const qqUpdate = async () => {
  loading.value = true;
  const result = await toggleState();
  err.value = result.stderr + "\n" +  result.stdout
  loading.value = false;
};
const refreshLog = async () => {
  logLoading.value = true;
  try {
    const result = await loadModuleLog();
    logPath.value = result.path;
    logText.value = result.text;
  } catch (error) {
    logText.value = String(error);
  } finally {
    logLoading.value = false;
  }
};
const clearLog = async () => {
  logLoading.value = true;
  try {
    await clearModuleLog();
    await refreshLog();
  } finally {
    logLoading.value = false;
  }
};
</script>

<template>
  <n-page-header title="QHook"> </n-page-header>
  <n-flex class="my-1em" style="gap: 8px 12px" vertical>
    <n-card
      title="设置"
      :segmented="{
        content: true,
        footer: 'soft',
      }"
    >
      <div class="form">
        <span>HOOK QQ</span>
        <n-switch
          :loading="loading"
          v-model:value="qqStatus"
          @update:value="qqUpdate"
        />
      </div>
    </n-card>
    <n-card title="模块日志" :segmented="{ content: true, footer: 'soft' }">
      <div class="log-actions">
        <n-button size="small" :loading="logLoading" @click="refreshLog">刷新</n-button>
        <n-button size="small" :loading="logLoading" @click="clearLog">清空</n-button>
      </div>
      <p class="log-path">{{ logPath }}</p>
      <pre class="log">{{ logText }}</pre>
    </n-card>
    {{ err }}
  </n-flex>
</template>

<style scoped>
.form {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.log-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.log-path {
  margin: 0 0 8px;
  font-size: 12px;
  color: #666;
  word-break: break-all;
}
.log {
  margin: 0;
  max-height: 360px;
  overflow: auto;
  padding: 8px;
  border-radius: 8px;
  background: #111;
  color: #d8d8d8;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
