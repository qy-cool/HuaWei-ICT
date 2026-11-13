import { execFile } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);
export const hash = bytes => createHash("sha256").update(bytes).digest("hex");
export const maxFileBytes = 64 * 1024;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

function safePath(name) {
  return typeof name === "string" && name.length <= 240 && name.split("/").every(
    part => /^[\p{L}\p{N}_. -]+$/u.test(part) && !/[. ]$/.test(part) &&
      !/^\.git(?:$|modules$|attributes$|config$)/i.test(part) &&
      !/^(?:con|prn|aux|nul|com\d|lpt\d)(?:\.|$)/i.test(part)
  );
}

export function validatePolicy(p) {
  if (!p || !path.isAbsolute(p.repository ?? "") || !path.isAbsolute(p.runDirectory ?? "") ||
      !/^refs\/heads\/[A-Za-z0-9_./-]+$/.test(p.ref) || p.ref.includes("..")) {
    throw new Error("invalid trusted repository, run directory or branch");
  }
  if (!Array.isArray(p.files) || !p.files.length || p.files.length > 32 ||
      !p.files.every(safePath) || new Set(p.files.map(f => f.toLowerCase())).size !== p.files.length) {
    throw new Error("policy must explicitly list up to 32 distinct text file paths");
  }
  if (!(p.confirmFiles ?? []).every(f => p.files.includes(f)) ||
      !(p.modes ?? ["build"]).every(m => ["build", "guarded", "yolo"].includes(m))) {
    throw new Error("invalid policy modes or protected files");
  }
  const url = new URL(p.modelUrl);
  if (!p.model || url.username || url.password || url.search || url.hash ||
      !(url.protocol === "https:" || (url.protocol === "http:" && ["localhost","127.0.0.1","[::1]"].includes(url.hostname)))) {
    throw new Error("model endpoint must use HTTPS or local loopback HTTP");
  }
}

function git(cwd, args, options = {}) {
  // Git is a host operation. No repository hooks, personal config or model
  // credentials enter these subprocesses. No repository-supplied executable runs.
  const env = {
    PATH: process.env.PATH, SystemRoot: process.env.SystemRoot,
    GIT_CONFIG_NOSYSTEM: "1", GIT_CONFIG_GLOBAL: "/dev/null", GIT_TERMINAL_PROMPT: "0",
    GIT_LITERAL_PATHSPECS: "1", GIT_ATTR_NOSYSTEM: "1", GIT_NO_REPLACE_OBJECTS: "1", LC_ALL: "C"
  };
  return run("git", ["-c", "core.hooksPath=/dev/null", ...args], {
    cwd, env, timeout: 15000, maxBuffer: 5 * 1024 * 1024, windowsHide: true, ...options
  }).then(result => result.stdout);
}

export async function openWorkspace(policy, task) {
  validatePolicy(policy);
  if (!uuid.test(task.taskId) || !/^[0-9a-f]{40}$/.test(task.baseSha)) throw new Error("invalid taskId or baseSha");
  const repository = await realpath(policy.repository);
  await mkdir(policy.runDirectory, { recursive: true, mode: 0o700 });
  const runs = await realpath(policy.runDirectory);
  if (runs === repository || runs.startsWith(repository + path.sep)) throw new Error("run directory must be outside source checkout");
  const sourceObjects = (await git(repository, ["rev-parse", "--path-format=absolute", "--git-path", "objects"])).trim();
  await git(repository, ["merge-base", "--is-ancestor", task.baseSha, policy.ref]);
  if ((await git(repository, ["cat-file", "-t", task.baseSha])).trim() !== "commit") throw new Error("base is not a commit");

  const dir = path.join(runs, task.taskId);
  const resume = task.type === "resume";
  if (!resume) await mkdir(dir, { mode: 0o700 });
  const lockPath = path.join(dir, "active.json");
  await writeFile(lockPath, JSON.stringify({pid:process.pid,opened:new Date().toISOString()}), {flag:"wx",mode:0o600});
  const release = () => unlink(lockPath);
  try {
    const scope = hash(JSON.stringify([repository, policy.ref, [...policy.files].sort(),
      [...(policy.confirmFiles ?? [])].sort(),policy.modelUrl,policy.model]));
    const authority = path.join(dir, "authority.json");
    const modes = ["build","guarded","yolo"];
    if (resume) {
      const old = JSON.parse(await readFile(authority, "utf8"));
      if (old.scope !== scope || old.baseSha !== task.baseSha || (old.plan && !task.plan) ||
          !modes.includes(old.mode) || modes.indexOf(task.mode ?? "build") > modes.indexOf(old.mode)) {
        throw new Error("resume conflicts with the original task scope");
      }
    } else {
      await writeFile(authority, JSON.stringify({scope,baseSha:task.baseSha,mode:task.mode ?? "build",plan:!!task.plan}), {flag:"wx",mode:0o600});
    }
    return await prepareFiles(policy,task,dir,sourceObjects,resume,release);
  } catch (err) {
    await release();
    throw err;
  }
}

async function prepareFiles(policy,task,dir,sourceObjects,resume,release) {
  const tree = path.join(dir, "worktree");
  if (!resume) {
    await mkdir(tree, { mode: 0o700 });
    await git(tree, ["init", "-q", "--template="]);
    await writeFile(path.join(tree, ".git", "objects", "info", "alternates"), sourceObjects + "\n", { mode: 0o600 });
    await git(tree, ["update-ref", "HEAD", task.baseSha]);
    await git(tree, ["read-tree", task.baseSha]);
  }
  const modes = new Map();
  // Materialize only declared blobs; symlink entries and submodules never
  // become filesystem paths. The source checkout and its dirty edits stay intact.
  for (const file of policy.files) {
    const entry = await git(tree, ["ls-files", "--stage", "-z", "--", file]);
    const dest = path.join(tree,file);
    if (!entry && resume) await unlink(dest).catch(err=>{if(err.code!=="ENOENT")throw err;});
    if (!entry) continue;
    const match = /^(100644|100755) ([a-f0-9]{40}) 0\t/.exec(entry);
    if (!match || entry.slice(match[0].length, -1) !== file) throw new Error("policy includes a non-file Git entry");
    if (Number((await git(tree, ["cat-file", "-s", match[2]])).trim()) > maxFileBytes) throw new Error("authorized file too large");
    const blob = await git(tree, ["cat-file", "blob", match[2]], { encoding: "buffer" });
    new TextDecoder("utf-8", { fatal: true }).decode(blob);
    if (blob.includes(0)) throw new Error("binary material is not supported");
    await mkdir(path.dirname(path.join(tree, file)), { recursive: true, mode: 0o700 });
    await writeFile(path.join(tree, file), blob, { mode: match[1] === "100755" ? 0o700 : 0o600 });
    modes.set(file, match[1]);
  }
  const patchPath = path.join(dir, "changes.patch");
  async function exportPatch() {
    const patch = await git(tree, ["diff", "--cached", "--no-textconv", "--no-ext-diff", "--binary", task.baseSha]);
    const patchTemp = path.join(dir, randomUUID());
    await writeFile(patchTemp, patch, { mode: 0o600, flag: "wx" });
    await rename(patchTemp, patchPath);
  }
  await exportPatch();

  function target(file) {
    if (!policy.files.includes(file)) throw new Error("file is outside project policy");
    return path.join(tree, file);
  }
  async function read(file) {
    const dest = target(file);
    try {
      if ((await stat(dest)).size > maxFileBytes) throw new Error("workspace file too large");
      const bytes = await readFile(dest);
      return { hash: hash(bytes), content: new TextDecoder("utf-8", { fatal: true }).decode(bytes) };
    } catch (error) {
      if (error.code === "ENOENT") return { hash: "missing", content: null };
      throw error;
    }
  }
  async function write(file, expectedHash, content) {
    if (typeof content !== "string" || content.includes("\0") || Buffer.byteLength(content) > maxFileBytes) throw new Error("invalid replacement text");
    if ((await read(file)).hash !== expectedHash) throw new Error("file changed since it was read");
    const dest = target(file);
    await mkdir(path.dirname(dest), { recursive: true, mode: 0o700 });
    const temp = path.join(dir, randomUUID());
    await writeFile(temp, content, { mode: modes.get(file) === "100755" ? 0o700 : 0o600, flag: "wx" });
    await rename(temp, dest);
    const oid = (await git(tree, ["hash-object", "-w", "--no-filters", "--", file])).trim();
    await git(tree, ["update-index", "--add", "--cacheinfo", modes.get(file) ?? "100644", oid, file]);
    await exportPatch();
    return hash(content);
  }
  return { dir, tree, patchPath, read, write, release };
}
