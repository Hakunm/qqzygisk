<script setup lang="ts">
import { getState, toggleState } from "./utils/state";

const err = ref("")
const qqStatus = ref(false);
onBeforeMount(async () => {
  qqStatus.value = !await getState();
})
const loading = ref(false);
const qqUpdate = async () => {
  loading.value = true;
  const result = await toggleState();
  err.value = result.stderr + "\n" +  result.stdout
  loading.value = false;
};
</script>

<template>
  <n-page-header title="QQ Zygisk"> </n-page-header>
  <n-flex class="my-1em" style="gap: 8px 12px">
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
    {{ err }}
  </n-flex>
</template>

<style scoped>
.form {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
