import { exec } from "kernelsu";

const LOG_PATHS = [
  "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qhook/qhook.log",
  "/data/user/0/com.tencent.mobileqq/files/qhook.log",
];

export type LogResult = {
  path: string;
  text: string;
};

export async function loadModuleLog(): Promise<LogResult> {
  for (const path of LOG_PATHS) {
    const quoted = path.replace(/"/g, '\\"');
    const result = await exec(`tail -n 200 "${quoted}"`);
    const text = (result.stdout || "").trim();
    if (text) {
      return { path, text };
    }
  }
  return {
    path: LOG_PATHS[0],
    text: "还没有日志文件。先在 QQ 里打开聊天，长按图片点保存，然后再回到这里刷新。",
  };
}

export async function clearModuleLog(): Promise<string> {
  const command = LOG_PATHS.map((path) => `rm -f "${path.replace(/"/g, '\\"')}"`).join(" ; ");
  const result = await exec(command);
  return (result.stderr || result.stdout || "已清空").toString();
}
