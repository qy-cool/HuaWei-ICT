import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { runTask } from "./task.mjs";
import { validatePolicy } from "./workspace.mjs";

// One profile serves exactly one (node, project, repository). Credentials and
// endpoints originate in administrator-managed files, outside both checkouts.
export async function serve(config, signal, { intervalMs = 1500 } = {}) {
  const base = new URL(config.controlUrl);
  if (!(base.protocol === "https:" || (base.protocol === "http:" && ["localhost","127.0.0.1","[::1]"].includes(base.hostname))) ||
      base.username || base.password || base.search || base.hash || base.pathname !== "/") throw new Error("invalid control origin");
  if (![config.serverId, config.projectId].every(n => Number.isSafeInteger(n) && n > 0) ||
      !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(config.repository) ||
      ![config.policyFile,config.nodeTokenFile,config.modelTokenFile].every(f => typeof f === "string" && path.isAbsolute(f))) {
    throw new Error("invalid local node profile");
  }
  const workerId = randomUUID();
  const poll = taskId => request("poll", {workerId,projectId:config.projectId,repository:config.repository,taskId});
  async function request(route,body) {
    const credential = (await readFile(config.nodeTokenFile,"utf8")).trim();
    const response = await fetch(new URL("/api/coding-agent/"+route,base),{
      method:"POST",redirect:"error",signal:AbortSignal.timeout(6000),
      headers:{"Content-Type":"application/json","X-Node-Id":String(config.serverId),"X-Node-Token":credential},
      body:JSON.stringify(body)
    });
    if (!response.ok) throw new Error("control response "+response.status);
    const reply=await response.json();
    if (reply.code!==0 && reply.code!==200) throw new Error("control request rejected");
    return reply.data;
  }
  let transportFailed=false;
  while (!signal.aborted) {
    try {
      const message=await poll(null);
      transportFailed=false;
      if (message.action==="start") await handle(message);
    } catch {
      if (!transportFailed) process.stderr.write("编码节点等待中控连接或有效凭据。\n");
      transportFailed=true;
    }
    await delay(intervalMs,undefined,{signal}).catch(()=>{});
  }

  async function handle(task) {
    const ctl=new AbortController();
    const taskSignal=AbortSignal.any([signal,ctl.signal,AbortSignal.timeout(15*60*1000)]);
    let waiting, text="",done=false,revoked=false,lastAck=0,pending=[],bytes=0;
    const enqueue=event=>{
      if (revoked) return;
      if (event.type==="text") {
        text+=event.text;
        if (text.length<8000) return;
      }
      flushText();
      if (event.type!=="text") pending.push(event);
      bytes+=JSON.stringify(event).length;
      if (bytes>8*1024*1024 || lastAck+pending.length>4000) ctl.abort();
    };
    function flushText() {
      while(text.length) {pending.push({type:"text",text:text.slice(0,8000)});text=text.slice(8000);}
    }
    function apply(command) {
      if (command.action==="cancel" || command.action==="stop") {
        if (command.revoked && !revoked) {
          revoked=true; pending=[];text=""; lastAck=command.lastSeq;
        }
        ctl.abort();
      } else if(command.action==="decision" && command.approvalId===waiting?.id) {
        if (!command.allow) enqueue({type:"declined",approvalId:command.approvalId});
        waiting.finish(command.allow===true);
      }
      else if(command.action!=="continue" && command.action!=="decision") ctl.abort();
    }
    async function authorize() {
      taskSignal.throwIfAborted();
      try { apply(await poll(task.taskId)); }
      catch (err) { ctl.abort();throw err; }
      taskSignal.throwIfAborted();
    }
    function approve(proposal,approvalSignal) {
      if (approvalSignal.aborted) return Promise.resolve(false);
      return new Promise(resolve=>{
        const abort=()=>finish(false);
        const finish=allow=>{waiting=undefined;approvalSignal.removeEventListener("abort",abort);resolve(allow);};
        waiting={id:proposal.approvalId,finish};
        approvalSignal.addEventListener("abort",abort,{once:true});
        enqueue(proposal);
      });
    }
    const run=(async()=>{
      let status="failed";
      try {
        const policy=JSON.parse(await readFile(config.policyFile,"utf8"));
        validatePolicy(policy);
        await authorize();
        const result=await runTask(policy,{...task,type:"start"},{
          token:(await readFile(config.modelTokenFile,"utf8")).trim(),signal:taskSignal,emit:enqueue,approve,authorize
        });
        status=result.status;
        if(!revoked) enqueue({type:"patch",text:await readFile(result.patchPath,"utf8")});
      } catch (err) { status=taskSignal.aborted ? "cancelled" : err.code==="EEXIST" ? "uncertain" : "failed"; }
      finally {
        enqueue({type:"finished",status,baseSha:task.baseSha});
        done=true;
      }
    })();
    let lastSuccess=Date.now();
    try {
      while (!signal.aborted) {
        try {
          const command=await poll(task.taskId);
          if(command.action==="stop") break;
          apply(command);
          if(revoked && done) pending=[{type:"finished",status:"cancelled"}];
          flushText();
          const head=pending[0];
          if(head) {
            const seq=lastAck+1;
            const ack=await request("events",{workerId,taskId:task.taskId,seq,event:head});
            if(ack!==seq) throw new Error("coding event acknowledgement mismatch");
            if(head===pending[0]) {lastAck=seq;pending.shift();}
            if(head.type==="finished") break;
          }
          lastSuccess=Date.now();
        } catch {
          if(Date.now()-lastSuccess>10000) ctl.abort();
          if(Date.now()-lastSuccess>90000) break;
          await delay(intervalMs,undefined,{signal}).catch(()=>{});
        }
        if(!pending.length) await delay(intervalMs,undefined,{signal}).catch(()=>{});
      }
    } finally { ctl.abort();await run; }
  }
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const stop=new AbortController();
  process.once("SIGINT",()=>stop.abort());
  process.once("SIGTERM",()=>stop.abort());
  await serve(JSON.parse(await readFile(process.argv[2],"utf8")),stop.signal);
}
