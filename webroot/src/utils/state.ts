import { exec } from "kernelsu";

const QQ_FILE = "/data/adb/qqhook/disableqq";

async function getState() {
  const { errno } = await exec(`test -e ${QQ_FILE}`);
  return errno == 0;
}

async function toggleState() {
  const state = await getState();
  if (state) {
    await exec(`rm ${QQ_FILE}`);
  } else {
    await exec(`touch ${QQ_FILE}`);
  }
}

export { getState, toggleState };
