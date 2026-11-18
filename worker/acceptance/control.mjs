// Real Java + MySQL acceptance. The only model endpoint is a loopback SSE fixture.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomBytes, randomUUID, createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { serve } from "../src/node.mjs";
import { identityFixture } from "./oidc.mjs";

const [jar,mysqld,java]=process.argv.slice(2);
if (![jar,mysqld,java].every(v=>v && path.isAbsolute(v))) throw new Error("usage: node acceptance/control.mjs <absolute server.jar> <absolute mysqld> <absolute java>");
const root=await mkdtemp(path.join(tmpdir(),"cloide-pi-accept-"));
const children=[],servers=[];
const stop=new AbortController();
let serving,shutdownDb;
const secret=()=>randomBytes(24).toString("hex");

function launch(exe,args,opts={}) {
  const proc=spawn(exe,args,{cwd:root,stdio:["pipe","pipe","pipe"],windowsHide:true,...opts});
  let log="";
  proc.stderr.on("data",b=>{log=(log+b).slice(-10000);});
  proc.stdout.on("data",b=>{log=(log+b).slice(-10000);});
  const exit=new Promise((resolve,reject)=>{proc.once("error",reject);proc.once("close",code=>resolve(code));});
  const child={proc,exit,log:()=>log};
  children.push(child);return child;
}
async function command(exe,args,opts) {
  const child=launch(exe,args,opts);
  const code=await child.exit;
  if(code!==0) throw new Error(path.basename(exe)+" failed, exit "+code);
  return child.log().trim();
}
async function listen(server) {
  await new Promise((resolve,reject)=>server.once("error",reject).listen(0,"127.0.0.1",resolve));
  return server.address().port;
}
async function sparePort() {
  const s=createServer(),p=await listen(s);
  await new Promise(resolve=>s.close(resolve)); return p;
}
async function until(check,seconds) {
  const end=Date.now()+seconds*1000;
  while(Date.now()<end){const result=await check();if(result)return result;await delay(200);}
  throw new Error("acceptance deadline reached");
}
try {
  const dbPort=await sparePort(),apiPort=await sparePort(),dbPass=secret(),bootstrap=secret();
  const identity=process.env.CLOIDE_TEST_OIDC==="1" ? await identityFixture(servers,listen,"http://127.0.0.1:"+apiPort+"/") : null;
  const data=path.join(root,"db"),init=path.join(root,"init.sql");
  const dbArgs=["--no-defaults","--datadir="+data,"--skip-log-bin","--innodb-buffer-pool-size=64M",
    ...(process.platform==="win32"?["--no-monitor"]:[])];
  await writeFile(init,`ALTER USER 'root'@'localhost' IDENTIFIED BY '${secret()}';
CREATE DATABASE coding_accept CHARACTER SET utf8mb4;
CREATE USER 'fixture'@'127.0.0.1' IDENTIFIED BY '${dbPass}';
GRANT ALL ON coding_accept.* TO 'fixture'@'127.0.0.1';
GRANT SHUTDOWN ON *.* TO 'fixture'@'127.0.0.1';\n`,{mode:0o600});
  await command(mysqld,[...dbArgs,"--initialize-insecure","--console"]);
  const db=launch(mysqld,[...dbArgs,"--bind-address=127.0.0.1","--port="+dbPort,"--mysqlx=0","--init-file="+init,"--console"]);
  await until(()=>{if(db.proc.exitCode!==null)throw new Error("temporary MySQL exited");return /ready for connections/i.test(db.log());},45);
  shutdownDb=()=>command(path.join(path.dirname(mysqld),"mysqladmin"+(process.platform==="win32"?".exe":"")),
    ["--no-defaults","--protocol=TCP","--host=127.0.0.1","--port="+dbPort,"--user=fixture","--shutdown-timeout=10","shutdown"],
    {env:{...process.env,MYSQL_PWD:dbPass}});
  await rm(init);
  const env={...process.env,DB_HOST:"127.0.0.1",DB_PORT:String(dbPort),DB_NAME:"coding_accept",DB_USER:"fixture",DB_PASSWORD:dbPass,
    JWT_SECRET:randomBytes(32).toString("base64"),AES_GCM_KEY:randomBytes(32).toString("base64"),AUTH_BOOTSTRAP_TOKEN:bootstrap,
    SERVER_ADDRESS:"127.0.0.1",SERVER_PORT:String(apiPort),APP_ENV:"prod",REDIS_ENABLED:"false",OUTBOX_ENABLED:"false",
    PROJECT_ISOLATION_ENABLED:"true",LOGGING_LEVEL_CLOIDE_SERVER_MODULE_AUTH_OIDC:"DEBUG",...identity?.variables};
  const app=launch(java,["-Xmx384m","-XX:ActiveProcessorCount=2","-jar",jar],{env});
  const origin="http://127.0.0.1:"+apiPort;
  let token;
  async function api(method,url,body) {
    const res=await fetch(origin+"/api"+url,{method,headers:{"Content-Type":"application/json",...(token?{Authorization:"Bearer "+token}:{})},
      body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});
    const envelope=await res.json();
    if (!res.ok || envelope.code!==0) throw new Error(method+" "+url+" HTTP "+res.status+" code "+envelope.code);
    return envelope.data;
  }
  await until(async()=>{if(app.proc.exitCode!==null)throw new Error("temporary Java server exited");try{return (await api("GET","/auth/bootstrap-status")).bootstrapPending;}catch{return false;}},65);
  const user={username:"coding_fixture",password:secret()};
  await api("POST","/auth/register",{...user,bootstrapToken:bootstrap});
  const login=await api("POST","/auth/login",user);
  token=login.token;
  if(identity) {
    try {await identity.verify(origin,api,login.user.id);}
    catch(err){console.error(app.log().match(/OIDC verification failed stage=\w+ error=\w+/g)??[]);throw err;}
  }
  const project=(await api("POST","/projects",{name:"coding-acceptance"})).id;
  const invite=await api("POST","/admin/node-enrollments",{name:"coding-node"});
  const node=await api("POST","/node-enrollments/redeem",{ticket:invite.ticket});
  await api("PUT",`/projects/${project}/environments/dev/nodes/${node.server_id}`);
  const source=path.join(root,"source");await mkdir(source);
  const git=(...args)=>command("git",["-c","core.autocrlf=false",...args],{cwd:source});
  await git("init","-q","-b","main","--template=");
  await writeFile(path.join(source,"orders.txt"),"base\n");
  await git("add",".");
  await git("-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","commit.gpgSign=false","commit","-qm","known");
  const sha=await git("rev-parse","HEAD");
  await writeFile(path.join(source,"orders.txt"),"human unsaved\n");
  let calls=0;
  const model=createServer(async(req,res)=>{
    for await(const _ of req) {}
    const delta=++calls===1?{role:"assistant",tool_calls:[{index:0,id:randomUUID(),type:"function",function:{
      name:"replace_project_file",arguments:JSON.stringify({path:"orders.txt",expectedHash:createHash("sha256").update("base\n").digest("hex"),content:"reviewed\n"})}}]}
      :{role:"assistant",content:"Reviewed."};
    const chunk=(d,reason)=>"data: "+JSON.stringify({id:"fixture",object:"chat.completion.chunk",model:"orders-test",created:1,
      choices:[{index:0,delta:d,finish_reason:reason}]})+"\n\n";
    res.writeHead(200,{"Content-Type":"text/event-stream"});
    res.end(chunk(delta,null)+chunk({},calls===1?"tool_calls":"stop")+"data: [DONE]\n\n");
  });
  servers.push(model);const modelPort=await listen(model);
  const config={controlUrl:origin,projectId:project,repository:"orders",serverId:node.server_id,
    policyFile:path.join(root,"policy.json"),modelTokenFile:path.join(root,"model-token"),nodeTokenFile:path.join(root,"node-token")};
  const policy={repository:source,runDirectory:path.join(root,"runs"),ref:"refs/heads/main",files:["orders.txt"],
    modes:["build"],model:"orders-test",modelUrl:`http://127.0.0.1:${modelPort}/v1`};
  await Promise.all([writeFile(config.nodeTokenFile,node.agent_token),writeFile(config.modelTokenFile,secret()),
    writeFile(config.policyFile,JSON.stringify(policy))]);
  serving=serve(config,stop.signal,{intervalMs:75});
  const list=`/projects/${project}/coding-tasks`;
  const saved=await api("POST",list,{requestId:randomUUID(),serverId:node.server_id,repository:"orders",baseSha:sha,prompt:"Update the order note."});
  const route=list+"/"+saved.taskId;
  const pending=await until(async()=>{const t=await api("GET",route);assert.ok(!["failed","uncertain"].includes(t.status));return t.approvalId;},30);
  const evidence=await api("GET",route+"/events?after=0");
  const proposal=evidence.find(e=>e.event.type==="approval").event;
  assert.equal(proposal.content,"reviewed\n");assert.equal(proposal.approvalId,pending);
  await api("POST",route+"/decision",{approvalId:pending,allow:true});
  await until(async()=>{const t=await api("GET",route);assert.ok(!["failed","uncertain"].includes(t.status));return t.status==="ready_for_review";},30);
  const events=await api("GET",route+"/events?after=0");
  assert.match(events.find(e=>e.event.type==="patch").event.text,/\+reviewed/);
  assert.equal(events.find(e=>e.event.type==="change").event.content,"reviewed\n");
  assert.equal(await readFile(path.join(source,"orders.txt"),"utf8"),"human unsaved\n");
  console.log(JSON.stringify({status:"ready_for_review",modelCalls:calls,events:events.map(e=>e.event.type),humanCheckoutPreserved:true}));
} finally {
  stop.abort();if(serving) await serving;
  for(const s of servers) {s.closeAllConnections();await new Promise(resolve=>s.close(resolve));}
  if(shutdownDb) await shutdownDb().catch(()=>{});
  for(const c of children.reverse()) {if(c.proc.exitCode===null && c.proc.signalCode===null)c.proc.kill();await c.exit.catch(()=>{});}
  await rm(root,{recursive:true,force:true,maxRetries:4});
  console.log("编码验收的临时实例、凭据和目录已清理。");
}
