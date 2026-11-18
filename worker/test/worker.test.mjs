import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { runTask } from "../src/task.mjs";
import { serve } from "../src/node.mjs";
import { hash, openWorkspace } from "../src/workspace.mjs";

async function fixture(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "cloide-worker-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const repository = path.join(dir, "source");
  await mkdir(repository);
  const git = (...args) => execFileSync("git", ["-c","core.autocrlf=false",...args], { cwd: repository, encoding: "utf8", windowsHide: true, timeout: 5000 }).trim();
  git("init", "-q", "--template=", "-b", "main");
  await writeFile(path.join(repository, "notes.txt"), "base\n");
  await writeFile(path.join(repository, "private.txt"), "never expose");
  git("add", ".");
  git("-c","user.name=Worker fixture","-c","user.email=fixture@example.invalid","-c","commit.gpgSign=false","commit","-qm","fixture");
  const baseSha = git("rev-parse","HEAD");
  await writeFile(path.join(repository, "notes.txt"), "developer uncommitted\n");
  return {
    policy: { repository, runDirectory: path.join(dir,"runs"), ref:"refs/heads/main",
      files:["notes.txt"], model:"test-chat", modelUrl:"http://127.0.0.1/v1", modes:["build","guarded","yolo"] },
    task: { type:"start",taskId:randomUUID(),baseSha,prompt:"Update the project note." }
  };
}

async function endpoint(t, handler) {
  let number = 0;
  const server = createServer(async (req, res) => {
    try {
      let body = "";
      for await (const chunk of req) body += chunk;
      handler(++number, JSON.parse(body), res, req);
    } catch (err) { res.destroy(err); }
  });
  await new Promise((resolve,reject) => server.once("error",reject).listen(0,"127.0.0.1",resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}/v1`;
}

function reply(res, tool, args) {
  res.writeHead(200,{"Content-Type":"text/event-stream"});
  const delta = tool
    ? {role:"assistant",tool_calls:[{index:0,id:randomUUID(),type:"function",function:{name:tool,arguments:JSON.stringify(args)}}]}
    : {role:"assistant",content:"Reviewed."};
  const chunk = (object, finish_reason) => "data: " + JSON.stringify({id:"chat-fixture",object:"chat.completion.chunk", model:"test-chat",created:1,
    choices:[{index:0,delta:object,finish_reason}]}) + "\n\n";
  res.end(chunk(delta,null) + chunk({},tool ? "tool_calls" : "stop") + "data: [DONE]\n\n");
}

test("actual Pi SDK asks for the exact edit and exports a patch against the selected commit", {timeout:30000}, async t => {
  const {policy,task} = await fixture(t);
  const offered = [], bodies=[];
  policy.modelUrl = await endpoint(t,(turn,body,res) => {
    offered.push(body.tools?.map(tool => tool.function.name).sort());
    bodies.push(body);
    if (turn === 1) reply(res,"read_project_file",{path:"notes.txt"});
    else if (turn === 2) reply(res,"replace_project_file",{path:"notes.txt",expectedHash:hash("base\n"),content:"changed\n"});
    else reply(res);
  });
  const approvals=[], events=[];
  const result = await runTask(policy,task,{
    token:"mock-model-token",signal:AbortSignal.timeout(20000),emit:event=>events.push(event),
    approve:async proposal=>{approvals.push(proposal);return true;}
  });
  assert.equal(result.status,"ready_for_review",JSON.stringify(events));
  assert.deepEqual(offered[0],["read_project_file","replace_project_file"]);
  assert.ok(JSON.stringify(bodies[1]).includes(hash("base\n")));
  assert.equal(approvals.length,1);
  assert.equal(approvals[0].afterHash,hash("changed\n"));
  assert.match(await readFile(result.patchPath,"utf8"),/\+changed/);
  assert.equal(await readFile(path.join(policy.repository,"notes.txt"),"utf8"),"developer uncommitted\n");
  assert.ok(!JSON.stringify(bodies).includes("never expose"));
  assert.ok(events.some(e=>e.type==="change"));
});

test("plan exposes only the read tool; sandbox paths and existing task IDs are bounded", {timeout:30000}, async t => {
  const {policy,task} = await fixture(t);
  let offered;
  policy.modelUrl = await endpoint(t,(_n,body,res)=>{offered=body.tools.map(tool=>tool.function.name);reply(res);});
  const result = await runTask(policy,{...task,plan:true},{token:"fixture",signal:AbortSignal.timeout(20000),
    emit:()=>{},approve:()=>assert.fail("plan cannot request writes")});
  assert.deepEqual(offered,["read_project_file"]);
  assert.equal(await readFile(result.patchPath,"utf8"),"");
  await assert.rejects(openWorkspace(policy,task),{code:"EEXIST"});
  const ws=await openWorkspace(policy,{...task,taskId:randomUUID()});
  try {
    await assert.rejects(ws.read("../private.txt"),/outside/);
    await assert.rejects(ws.write("private.txt","missing","oops"),/outside/);
    await assert.rejects(ws.write("notes.txt",hash("wrong"),"oops"),/changed/);
  } finally {
    await ws.release();
  }
});

test("JSONL supervisor receives approvals and a new process can resume the exact session", {timeout:40000}, async t=>{
  const {policy,task}=await fixture(t);
  let resumedBody;
  policy.modelUrl=await endpoint(t,(n,body,res)=>{
    if(n===1)reply(res,"replace_project_file",{path:"notes.txt",expectedHash:hash("base\n"),content:"reviewed\n"});
    else {if(n===3)resumedBody=body;reply(res);}
  });
  const profile=path.join(path.dirname(policy.repository),"policy.json");
  await writeFile(profile,JSON.stringify(policy));
  async function supervise(frame) {
    const child=spawn(process.execPath,[fileURLToPath(new URL("../src/main.mjs",import.meta.url)),profile],{
      env:{...process.env,CLOIDE_MODEL_TOKEN:"fixture"},windowsHide:true,stdio:["pipe","pipe","pipe"],
      signal:AbortSignal.timeout(15000)
    });
    const lines=createInterface({input:child.stdout}),events=[];
    let errors="";
    child.stderr.on("data",buffer=>{errors+=buffer;});
    const exit=new Promise((resolve,reject)=>{child.once("error",reject);child.once("close",resolve);});
    lines.on("line",line=>{
      const event=JSON.parse(line);events.push(event);
      if(event.type==="approval")child.stdin.write(JSON.stringify({type:"decision",approvalId:event.approvalId,allow:true})+"\n");
      if(event.type==="finished")child.stdin.end();
    });
    child.stdin.write(JSON.stringify(frame)+"\n");
    try { assert.equal(await exit,0,errors+JSON.stringify(events)); }
    finally { lines.close();child.kill(); }
    return events;
  }
  const first=await supervise(task);
  assert.ok(first.some(e=>e.type==="approval"&&e.beforeHash===hash("base\n")));
  assert.equal(first.at(-1).status,"ready_for_review");
  const second=await supervise({...task,type:"resume",prompt:"Review the existing change."});
  assert.equal(second.at(-1).status,"ready_for_review");
  assert.ok(JSON.stringify(resumedBody.messages).includes("Update the project note."));
  assert.match(await readFile(second.at(-1).patchPath,"utf8"),/\+reviewed/);
  await assert.rejects(openWorkspace({...policy,files:["private.txt"]},{...task,type:"resume"}),/scope/);
  await assert.rejects(openWorkspace(policy,{...task,type:"resume",mode:"yolo"}),/scope/);
});

test("cancelling a live provider stream stops the actual SDK session", {timeout:30000}, async t => {
  const {policy,task} = await fixture(t);
  const ctl = new AbortController();
  policy.modelUrl = await endpoint(t,(_n,_body,res)=>{
    res.writeHead(200,{"Content-Type":"text/event-stream"});
    res.flushHeaders();
    ctl.abort();
  });
  const result = await runTask(policy,task,{token:"fixture",signal:AbortSignal.any([ctl.signal,AbortSignal.timeout(20000)]),
    emit:()=>{},approve:()=>assert.fail()});
  assert.equal(result.status,"cancelled");
});

test("outbound coding node claims a scoped task, awaits its bound decision and retries the same receipt", {timeout:30000}, async t=>{
  const {policy,task}=await fixture(t);
  policy.modelUrl=await endpoint(t,(turn,_body,res)=>{
    if (turn===1) reply(res,"replace_project_file",{path:"notes.txt",expectedHash:hash("base\n"),content:"remote\n"});
    else reply(res);
  });
  const root=path.dirname(policy.repository);
  const config={serverId:4,projectId:2,repository:"orders",
    policyFile:path.join(root,"policy.json"),nodeTokenFile:path.join(root,"node.token"),modelTokenFile:path.join(root,"model.token")};
  await Promise.all([writeFile(config.policyFile,JSON.stringify(policy)),writeFile(config.nodeTokenFile,"node-proof"),writeFile(config.modelTokenFile,"model-proof")]);
  const events=new Map(),ctl=new AbortController();
  let approval,duplicate=false,assigned=false;
  const control=await endpoint(t,(_n,body,res,req)=>{
    assert.equal(req.headers["x-node-token"],"node-proof");
    assert.equal(req.headers["x-node-id"],"4");
    assert.ok(!JSON.stringify(body).includes("model-proof"));
    let data;
    if(req.url.endsWith("/poll")) {
      assert.equal(body.projectId,2);assert.equal(body.repository,"orders");
      if(!body.taskId && !assigned) {assigned=true;data={action:"start",...task};}
      else if(approval) data={action:"decision",approvalId:approval,allow:true};
      else data={action:"continue"};
    } else {
      assert.equal(req.url,"/api/coding-agent/events");
      if(events.has(body.seq)){assert.deepEqual(events.get(body.seq),body.event);duplicate=true;}
      events.set(body.seq,body.event);
      if(body.event.type==="approval")approval=body.event.approvalId;
      if(body.seq===1 && !duplicate) {res.destroy();return;}
      data=body.seq;
      if(body.event.type==="finished") res.once("finish",()=>ctl.abort());
    }
    res.writeHead(200,{"content-type":"application/json"});res.end(JSON.stringify({code:0,data}));
  });
  config.controlUrl=new URL(control).origin;
  await serve(config,AbortSignal.any([ctl.signal,AbortSignal.timeout(18000)]),{intervalMs:20});
  const log=[...events.values()];
  assert.equal(log.at(-1)?.status,"ready_for_review");
  assert.ok(duplicate);assert.ok(approval);
  assert.match(log.find(e=>e.type==="patch").text,/\+remote/);
  assert.equal(await readFile(path.join(policy.repository,"notes.txt"),"utf8"),"developer uncommitted\n");
});
