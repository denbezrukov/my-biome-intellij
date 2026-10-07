#!/usr/bin/env node
// Transparent test-only relay: the plugin still starts and talks to the pinned Biome.
const { spawn } = require("node:child_process");
const { appendFileSync } = require("node:fs");
const executable = __BIOME_EXECUTABLE__;
const transcript = __LSP_TRANSCRIPT__;
const child = spawn(executable, process.argv.slice(2), {
  stdio: ["pipe", "pipe", "inherit"],
});
const isLsp = process.argv.includes("lsp-proxy");

function recorder(direction) {
  let pending = Buffer.alloc(0);
  return (data) => {
    if (!isLsp) return;
    pending = Buffer.concat([pending, data]);
    while (true) {
      const headerEnd = pending.indexOf("\r\n\r\n");
      if (headerEnd < 0) return;
      const header = pending.subarray(0, headerEnd).toString("ascii");
      const match = /^Content-Length:\s*(\d+)$/im.exec(header);
      if (!match) throw new Error(`Invalid LSP header: ${header}`);
      const bodyStart = headerEnd + 4;
      const bodyEnd = bodyStart + Number(match[1]);
      if (pending.length < bodyEnd) return;
      const message = JSON.parse(pending.subarray(bodyStart, bodyEnd).toString("utf8"));
      appendFileSync(transcript, JSON.stringify({ direction, message }) + "\n");
      pending = pending.subarray(bodyEnd);
    }
  };
}

process.stdin.on("data", recorder("client"));
child.stdout.on("data", recorder("server"));
process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => child.kill(signal));
}
child.on("error", (error) => {
  console.error(error);
  process.exitCode = 1;
});
child.on("exit", (code, signal) => {
  process.exitCode = code ?? (signal ? 1 : 0);
  process.stdin.destroy();
});
