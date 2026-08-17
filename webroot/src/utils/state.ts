import { exec, moduleInfo } from "kernelsu";

const info = JSON.parse(moduleInfo());
const QQ_FILE = `/data/${info.dir}/packages/com.tencent.mobileqq`;

async function getState() {
  const { errno } = await exec(`test -e ${QQ_FILE}`);
  return errno == 0;
}

async function toggleState() {
  const state = await getState();
  if (state) {
    return await exec(`rm ${QQ_FILE}`);
  } else {
    return await exec(`touch ${QQ_FILE}`);
  }
}

export { getState, toggleState };
