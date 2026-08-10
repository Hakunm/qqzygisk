interface ExecOptions {}

interface ExecResult {
  errno: number;
  stdout: string;
  stderr: string;
}

interface Stdio {
  listeners: { [event: string]: ((...args: any[]) => void)[] };
  on(event: string, listener: (...args: any[]) => void): void;
  emit(event: string, ...args: any[]): void;
}

interface ChildProcess {
  listeners: { [event: string]: ((...args: any[]) => void)[] };
  stdin: Stdio;
  stdout: Stdio;
  stderr: Stdio;
  on(event: "exit", listener: (code: number) => void): void;
  on(event: "error", listener: (error: Error) => void): void;
  on(event: string, listener: (...args: any[]) => void): void;
  emit(event: string, ...args: any[]): void;
}

declare module "kernelsu" {
  function exec(command: string, options?: ExecOptions): Promise<ExecResult>;
  function spawn(
    command: string,
    args?: string[],
    options?: object
  ): ChildProcess;
  function fullScreen(isFullScreen: boolean): void;
  function toast(message: string): void;

  export { exec, spawn, fullScreen, toast };
}
