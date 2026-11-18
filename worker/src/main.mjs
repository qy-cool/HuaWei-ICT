import { readFile } from "node:fs/promises";
import { createInterface } from "node:readline";
import { runTask } from "./task.mjs";

// One supervisor process owns both streams. Model-generated content is always
// JSON data on stdout; only the supervisor can issue a stdin approval.
const policy = JSON.parse(await readFile(process.argv[2], "utf8"));
const input = createInterface({ input: process.stdin, terminal: false });
const control = new AbortController();
const signal = AbortSignal.any([control.signal, AbortSignal.timeout(15 * 60 * 1000)]);
const waiting = new Map();
let sequence = 0;
let job;
const emit = event => process.stdout.write(JSON.stringify({ seq: ++sequence, ...event }) + "\n");

function approve(proposal, approvalSignal) {
  if (approvalSignal.aborted) return Promise.resolve(false);
  return new Promise(resolve => {
    const aborted = () => finish(false);
    const finish = value => {
      waiting.delete(proposal.approvalId);
      approvalSignal.removeEventListener("abort", aborted);
      resolve(value);
    };
    waiting.set(proposal.approvalId, finish);
    approvalSignal.addEventListener("abort", aborted, { once: true });
    emit(proposal);
  });
}
process.once("SIGTERM", () => control.abort());
process.once("SIGINT", () => control.abort());
input.on("line", line => {
  try {
    if (Buffer.byteLength(line) > 65536) throw new Error("oversized control frame");
    const frame = JSON.parse(line);
    if (["start","resume"].includes(frame.type) && !job) {
      job = runTask(policy, frame, { token: process.env.CLOIDE_MODEL_TOKEN, signal, emit, approve })
        .then(result => { emit(result); if (result.status === "failed") process.exitCode = 1; })
        .catch(() => { emit({ type: "finished", status: signal.aborted ? "cancelled" : "failed", error: "task initialization or execution failed" }); process.exitCode = 1; })
        .finally(() => { input.close(); process.stdin.pause(); });
    } else if (frame.type === "decision" && typeof frame.allow === "boolean" && waiting.has(frame.approvalId)) {
      waiting.get(frame.approvalId)(frame.allow);
    } else if (frame.type === "cancel" && job) {
      control.abort();
    } else {
      throw new Error("unexpected control frame");
    }
  } catch (error) {
    emit({ type: "input_error", error: error instanceof SyntaxError ? "invalid JSON" : error.message });
  }
});
input.once("close", () => control.abort());
