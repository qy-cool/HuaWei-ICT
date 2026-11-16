import { queryJava } from "./java-symbols.mjs";
import { randomUUID } from "node:crypto";
import path from "node:path";
import { mkdir, readdir } from "node:fs/promises";
import {
  createAgentSession, createExtensionRuntime, ModelRuntime,
  SessionManager, SettingsManager
} from "@earendil-works/pi-coding-agent";
import { hash, maxFileBytes, openWorkspace } from "./workspace.mjs";

function managedResources(prompt) {
  const runtime = createExtensionRuntime();
  return {
    getExtensions: () => ({ extensions: [], errors: [], runtime }),
    getSkills: () => ({ skills: [], diagnostics: [] }),
    getPrompts: () => ({ prompts: [], diagnostics: [] }),
    getThemes: () => ({ themes: [], diagnostics: [] }),
    getAgentsFiles: () => ({ agentsFiles: [] }),
    getSystemPrompt: () => prompt,
    getSystemPromptSource: () => undefined,
    getAppendSystemPrompt: () => [],
    getAppendSystemPromptSources: () => [],
    extendResources() {}, async reload() {}
  };
}

function objectSchema(properties) {
  return { type: "object", properties, required: Object.keys(properties), additionalProperties: false };
}
const toolResult = value => ({ content: [{ type: "text", text: JSON.stringify(value) }], details: {} });

export async function runTask(policy, task, { token, signal, emit, approve, authorize = async () => {} }) {
  const mode = task.mode ?? "build";
  if (!(policy.modes ?? ["build"]).includes(mode) || (task.plan !== undefined && typeof task.plan !== "boolean") ||
      typeof task.prompt !== "string" || !task.prompt.trim() || Buffer.byteLength(task.prompt) > 32768) {
    throw new Error("task mode or prompt is outside local policy");
  }
  if (!token) throw new Error("missing task-scoped model token");
  signal.throwIfAborted();
  const ws = await openWorkspace(policy, task);
  try {
    return await runSession(policy, task, mode, ws, {token, signal, emit, approve, authorize});
  } finally {
    await ws.release();
  }
}

async function runSession(policy, task, mode, ws, {token, signal, emit, approve, authorize}) {
  emit({ type: "started", taskId: task.taskId, baseSha: task.baseSha, mode, plan: !!task.plan });
  const runtime = await ModelRuntime.create({
    authPath: path.join(ws.dir, "pi-auth.json"), modelsPath: null, allowModelNetwork: false, refreshOnCreate: false, signal
  });
  runtime.registerProvider("enterprise", {
    api: "openai-completions", baseUrl: policy.modelUrl,
    apiKey: "CLOIDE_MODEL_TOKEN", models: [{
      id: policy.model, name: policy.model, reasoning: false, input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 32000, maxTokens: 4096
    }]
  });
  await runtime.setRuntimeApiKey("enterprise", token);
  const pathSchema = { type: "string", enum: policy.files };
  const tools = [{
    name: "read_project_file", label: "读取项目文件",
    description: "Read one UTF-8 project file and its exact SHA-256 hash. Missing files have hash missing.",
    parameters: objectSchema({ path: pathSchema }),
    execute: async (_id, args) => { await authorize(); return toolResult(await ws.read(args.path)); }
  }];
  if (policy.javaAnalysis) tools.push({
    name: "query_java_symbols", label: "查询 Java 符号",
    description: "Find a qualified Java class or Class#method in one allowed file and return source lines and digest.",
    parameters: objectSchema({ path: pathSchema, symbol: {type:"string"} }),
    execute: async (_id, args, toolSignal) => {
      await authorize();
      const hits = await queryJava(policy.javaAnalysis, args.path, args.symbol, await ws.read(args.path), toolSignal ?? signal);
      await authorize();
      return toolResult(hits);
    }
  });
  // The SDK may dispatch multiple tool calls per turn. Serialize edits and
  // recheck the digest after the human decision, just before writing.
  let pendingWrite = Promise.resolve();
  if (!task.plan) tools.push({
    name: "replace_project_file", label: "修改项目文件",
    description: "Replace one permitted file; provide the hash returned by the latest read.",
    parameters: objectSchema({ path: pathSchema, expectedHash: { type: "string" }, content: { type: "string" } }),
    execute: (_id, args, toolSignal) => {
      const current = pendingWrite.then(async () => {
        signal.throwIfAborted();
        await authorize();
        const before = await ws.read(args.path);
        if (before.hash !== args.expectedHash) throw new Error("stale file digest");
        if (typeof args.content !== "string" || Buffer.byteLength(args.content) > maxFileBytes) throw new Error("replacement too large");
        const nextHash = hash(args.content);
        if (mode === "build" || (policy.confirmFiles ?? []).includes(args.path)) {
          const proposal = {
            type: "approval", approvalId: randomUUID(), taskId: task.taskId, baseSha: task.baseSha,
            path: args.path, beforeHash: before.hash, afterHash: nextHash, content: args.content
          };
          if (!await approve(proposal, toolSignal ? AbortSignal.any([signal, toolSignal]) : signal)) throw new Error("edit declined");
        }
        signal.throwIfAborted();
        toolSignal?.throwIfAborted();
        await authorize();
        const newHash = await ws.write(args.path, args.expectedHash, args.content);
        emit({ type: "change", path: args.path, beforeHash: before.hash, afterHash: newHash, content: args.content });
        return toolResult({ hash: newHash });
      });
      pendingWrite = current.catch(() => {});
      return current;
    }
  });
  const prompt = `You are working in an isolated project task. Available files: ${JSON.stringify(policy.files)}. ` +
    (task.plan ? "Read files and propose a plan." : "Read before edits. Use only the provided project tools.") +
    " Report changes accurately; tests and deployment are separate platform jobs.";
  const sessions = path.join(ws.dir,"sessions");
  await mkdir(sessions,{recursive:true,mode:0o700});
  const existing = (await readdir(sessions)).filter(name => name.endsWith(".jsonl"));
  if (existing.length > 1) throw new Error("ambiguous task session");
  const sessionManager = existing.length && task.type === "resume"
    ? SessionManager.open(path.join(sessions,existing[0]),sessions,ws.tree)
    : SessionManager.create(ws.tree,sessions);
  const { session } = await createAgentSession({
    cwd: ws.tree, agentDir: path.join(ws.dir, "pi"), modelRuntime: runtime,
    model: runtime.getModel("enterprise", policy.model), thinkingLevel: "off",
    customTools: tools, tools: tools.map(tool => tool.name), resourceLoader: managedResources(prompt),
    sessionManager,
    settingsManager: SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false } })
  });
  let turns = 0;
  let stopped = false;
  let budgetExhausted = false;
  let providerFailed = false;
  const cancel = () => { void session.abort(); };
  const unsubscribe = session.subscribe(event => {
    if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
      emit({ type: "text", text: event.assistantMessageEvent.delta });
    }
    if (event.type === "message_end" && event.message.role === "assistant") {
      if (event.message.stopReason === "error") providerFailed = true;
      if (++turns >= 12 && event.message.stopReason === "toolUse") { budgetExhausted = true; cancel(); }
    }
    if (event.type === "agent_settled") stopped = event.aborted;
  });
  signal.addEventListener("abort", cancel, { once: true });
  try {
    signal.throwIfAborted();
    await session.prompt(task.prompt, { expandPromptTemplates: false });
    const status = providerFailed || budgetExhausted ? "failed" : signal.aborted || stopped ? "cancelled" : "ready_for_review";
    return { type: "finished", status, baseSha: task.baseSha, patchPath: ws.patchPath, turns };
  } finally {
    signal.removeEventListener("abort", cancel);
    unsubscribe();
    session.dispose();
  }
}
