#!/usr/bin/env python3
"""以两台临时 Linux 实例、真实 Edge 和 code-server 验证工作树持久及网关撤权。"""
import argparse
import asyncio
import importlib.util
import json
import hashlib
import os
import re
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
from urllib.parse import urlsplit
import aiohttp
from playwright.async_api import async_playwright
from project_release_checks import ReleaseChecks

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("jobs_e2e",HERE/"run-project-jobs-e2e.py")
jobs=importlib.util.module_from_spec(spec);spec.loader.exec_module(jobs)
HOST="orders.ide.example.test"


async def main(opts):
    central=jobs.Fixture(opts.central)
    worker=jobs.Fixture(opts.worker)
    tls=ReleaseChecks()
    guard=None
    front=None
    try:
        print("正在准备两机临时目录和官方制品",flush=True)
        with opts.code_server.open("rb") as archive:
            jobs.require(hashlib.file_digest(archive,"sha256").hexdigest()=="e867a34416e2da4a83496b09580001893e7a4e5bb58a0624c4fcd22819e7d53d",
                         "本地 code-server 安装包摘要不符")
        await central.upload(opts.jar,"server.jar")
        await worker.upload(HERE/"workspace-linux-fixture.py","fixture.py")
        upstream="https://github.com/coder/code-server/releases/download/v4.145.2/code-server-4.145.2-linux-arm64.tar.gz"
        if opts.worker_archive:
            if not opts.worker_archive.startswith("/") or "\n" in opts.worker_archive:
                raise ValueError("节点已有安装包需要绝对路径")
            await jobs.cmd("ssh",*jobs.SSH,worker.host,f"cp -- {shlex.quote(opts.worker_archive)} {worker.directory}/code-server.tar.gz")
        else:
            try:
                await jobs.cmd("ssh",*jobs.SSH,worker.host,
                    f"curl -q -fsSL --connect-timeout 8 --speed-limit 262144 --speed-time 15 --max-time 240 -o {worker.directory}/code-server.tar.gz {upstream}",timeout=260)
            except (RuntimeError,TimeoutError):
                await jobs.cmd("scp","-q","-O",*jobs.SSH,str(opts.code_server),f"{worker.host}:{worker.directory}/code-server.tar.gz",timeout=1200)
        for src,dest in ((HERE/"workspace-linux-fixture.py","fixture.py"),(HERE.parents[1]/"deploy/workspace/node.py","workspace-node.py"),
                         (HERE.parents[1]/"deploy/workspace/socket_relay.py","socket_relay.py")):
            await jobs.cmd("scp","-q","-O",*jobs.SSH,str(src),f"{worker.host}:{worker.directory}/{dest}")
        await jobs.cmd("ssh",*jobs.SSH,worker.host,f"chmod 644 {worker.directory}/workspace-node.py")
        key=secrets.token_urlsafe(42)
        api_port=jobs.free_port()
        central_port=39000+secrets.randbelow(9000)
        reply=await central.start("ide-central",central_port,["-L",f"127.0.0.1:{api_port}:127.0.0.1:{central_port}"],{"gateway_key":key})
        print("独立中控已就绪，Flyway "+reply["flyway"],flush=True)
        async with aiohttp.ClientSession(trust_env=False) as client:
            url=f"http://127.0.0.1:{api_port}/api"
            jwt=None
            async def api(method,path,body=None):
                headers={"Authorization":"Bearer "+jwt} if jwt else {}
                async with client.request(method,url+path,json=body,headers=headers) as r:
                    data=await r.json()
                    jobs.require(r.status==200 and data["code"]==0,f"{method} {path}: HTTP {r.status}, code {data['code']}")
                    return data["data"]
            login={"username":"ide_operator","password":secrets.token_urlsafe(24)}
            await api("POST","/auth/register",{**login,"bootstrapToken":reply["bootstrap"]})
            session=await api("POST","/auth/login",login);jwt=session["token"];user=session["user"]["id"]
            project=(await api("POST","/projects",{"name":"ide-smoke"}))["id"]
            ticket=(await api("POST","/admin/node-enrollments",{"name":"ide-test"}))["ticket"]
            identity=await api("POST","/node-enrollments/redeem",{"ticket":ticket})
            node=identity["server_id"]
            await api("PUT",f"/projects/{project}/environments/dev/nodes/{node}")
            work=f"/projects/{project}/workspaces"
            registered=await api("POST",work,{"serverId":node,"ownerId":user,"slug":"orders","name":"orders","managed":True})
            wid=registered["id"];resource=work+"/"+wid
            tls_port,node_tls,ide_port=jobs.free_port(),51000+secrets.randbelow(10000),jobs.free_port()
            await tls.listen(client,api_port,tls_port,"/api/workspace-agent")
            await worker.start("workspace",node_tls,["-R",f"127.0.0.1:{node_tls}:127.0.0.1:{tls_port}",
                "-L",f"127.0.0.1:{ide_port}:{worker.directory}/private/gateway.sock"],{**identity,"project_id":project,
                "control":f"https://127.0.0.1:{node_tls}","workspace_id":wid,"hostname":HOST,"ca":tls.ca})
            print("ARM 节点管理器已启动，等待 code-server 健康回执",flush=True)
            async def observe(state):
                for _ in range(60):
                    row=(await api("GET",work))[0]
                    if row["desired"]==row["observed"]==state and row["generation"]==row["observedGeneration"]:return row
                    if row["observed"]=="failed" and row["generation"]==row["observedGeneration"]:
                        break
                    await asyncio.sleep(.4)
                # Local paths and secrets are excluded. These two indicators help identify a host capability failure.
                diag=await jobs.cmd("ssh",*jobs.SSH,worker.host,f"tail -n 5 {worker.directory}/manager.log")
                print("manager diagnostic:",diag.replace(worker.directory,"<fixture>")[-700:],flush=True)
                raise RuntimeError("workspace did not reach "+state)
            await observe("running")
            guard_port=jobs.free_port()
            guard=await asyncio.create_subprocess_exec(str(opts.guard.resolve()),"-host",HOST,"-listen",f"127.0.0.1:{guard_port}",
                "-ide",f"127.0.0.1:{ide_port}","-control",f"127.0.0.1:{api_port}",env={**os.environ,"WORKSPACE_GATEWAY_KEY":key},
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            tunnels=set()
            async def relay(reader,writer):
                task=asyncio.current_task();tunnels.add(task)
                upstream=None
                try:
                    rr,upstream=await asyncio.open_connection("127.0.0.1",guard_port)
                    async def copy(src,dst):
                        try:
                            while data:=await src.read(65536):
                                dst.write(data);await dst.drain()
                        finally:dst.close()
                    await asyncio.gather(copy(reader,upstream),copy(rr,writer),return_exceptions=True)
                finally:
                    writer.close()
                    if upstream: upstream.close()
                    tunnels.discard(task)
            browser_port=jobs.free_port()
            front=await asyncio.start_server(relay,"127.0.0.1",browser_port,ssl=tls.ssl)
            async with async_playwright() as pw:
                browser=await pw.chromium.launch(executable_path=opts.browser_binary,args=[
                    "--no-proxy-server","--disable-background-networking",f"--host-resolver-rules=MAP {HOST}:443 127.0.0.1:{browser_port}"])
                try:
                    async def open_page():
                        entry=await api("POST",resource+"/tickets")
                        async with client.post(url+"/workspace-gateway/session",data="ticket="+entry["ticket"],
                                headers={"X-Cloide-Gateway-Key":key,"X-Cloide-Workspace-Host":HOST,
                                         "X-Cloide-Browser-Origin":"https://console.example.test","Content-Type":"text/plain"}) as res:
                            jobs.require(res.status==200,"工作区票据兑换失败")
                            value=res.cookies["__Host-cloide-workspace"].value
                        ctx=await browser.new_context(ignore_https_errors=True,locale="en-US")
                        await ctx.add_cookies([{"name":"__Host-cloide-workspace","value":value,"url":"https://"+HOST,
                                                "secure":True,"httpOnly":True,"sameSite":"Strict"}])
                        await ctx.route("**/*",lambda route: route.continue_() if urlsplit(route.request.url).hostname==HOST else route.abort())
                        page=await ctx.new_page()
                        sockets=[]
                        def watch(ws):
                            closed=asyncio.Event();sockets.append(closed);ws.on("close",lambda *_:closed.set())
                        page.on("websocket",watch)
                        await page.goto("https://"+HOST+"/?folder=/work",wait_until="domcontentloaded")
                        await page.locator(".monaco-workbench").wait_for(timeout=60000)
                        return ctx,page,sockets
                    async def editor(page):
                        trust=page.get_by_role("button",name="Yes, I trust the authors")
                        if await trust.count():await trust.click(timeout=3000)
                        file=page.get_by_role("treeitem",name="acceptance.txt",exact=True)
                        if await file.count() and await file.first.is_visible():
                            await file.first.dblclick()
                            return page.locator(".monaco-editor .view-lines").first
                        await page.keyboard.press("Control+p")
                        quick=page.locator(".quick-input-widget input").first
                        await quick.fill("acceptance.txt",timeout=12000)
                        await page.locator(".quick-input-list").get_by_text("acceptance.txt",exact=True).first.click(timeout=15000)
                        return page.locator(".monaco-editor .view-lines").first
                    ctx,page,sockets=await open_page()
                    view=await editor(page)
                    await view.get_by_text("initial-workspace",exact=False).first.wait_for(timeout=20000)
                    await view.click()
                    await page.keyboard.press("Control+a")
                    await page.keyboard.insert_text("workspace-persistence-check")
                    await page.keyboard.press("Control+s")
                    for _ in range(12):
                        got=await jobs.cmd("ssh",*jobs.SSH,worker.host,f"cat {worker.directory}/repo/acceptance.txt")
                        if got=="workspace-persistence-check":break
                        await asyncio.sleep(.3)
                    jobs.require(got=="workspace-persistence-check","浏览器保存的改动未落盘")
                    await api("POST",resource+"/sleep");await observe("sleeping");await ctx.close()
                    await api("POST",resource+"/wake");await observe("running")
                    ctx,page,sockets=await open_page()
                    view=await editor(page)
                    await view.get_by_text("workspace-persistence-check",exact=False).first.wait_for(timeout=20000)
                    print("浏览器保存的未提交文件已在休眠恢复后重新打开",flush=True)
                    terminal_ok=False
                    if opts.terminal:
                        try:
                            async def run_test_command():
                                await page.keyboard.press("Control+Shift+p")
                                commands=page.locator(".quick-input-widget input").first
                                await commands.fill(">Tasks: Run Test Task")
                                await page.locator(".quick-input-list").get_by_text("Tasks: Run Test Task",exact=True).first.click(timeout=8000)
                                choose=page.locator(".quick-input-list").get_by_text("Verify saved workspace",exact=True)
                                if await choose.count() and await choose.first.is_visible():await choose.first.click()
                            await run_test_command()
                            trusted=False
                            for attempt in range(12):
                                # In a fresh isolated profile VS Code asks again when a trusted task is first executed.
                                # The generated fixture and its verify.sh are controlled by this test.
                                consent=page.get_by_role("button",name=re.compile(r"^(?:Yes, I trust|Trust(?: Folder| Workspace)?(?: &| and|$))",re.I))
                                if await consent.count() and await consent.first.is_visible():
                                    await consent.first.click(timeout=3000)
                                    trusted=True
                                try:
                                    got=await jobs.cmd("ssh",*jobs.SSH,worker.host,f"cat {worker.directory}/repo/ide-test-result.txt")
                                    terminal_ok=got=="workspace-ide-test-ok"
                                except RuntimeError:
                                    pass
                                if terminal_ok: break
                                if trusted and attempt==6:await run_test_command()
                                await asyncio.sleep(.4)
                        except Exception:
                            terminal_ok=False
                        if not terminal_ok:
                            print("terminal diagnostic:",await page.evaluate("""() => ({
                              terminals:document.querySelectorAll('.xterm').length,
                              notices:[...document.querySelectorAll('.notification-list-item-message,.dialog-message-text')].map(x=>x.textContent.slice(0,160)),
                              buttons:[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].map(x=>x.textContent.slice(0,60)),
                              choices:document.querySelector('.quick-input-widget:not([style*="display: none"])')?.innerText?.slice(0,220)
                            })"""),flush=True)
                    active=[e for e in sockets if not e.is_set()]
                    jobs.require(len(active)>0,"实际 IDE 没有已建立的 WebSocket")
                    await api("DELETE",f"/projects/{project}/environments/dev/nodes/{node}")
                    await asyncio.wait_for(asyncio.gather(*(e.wait() for e in active)),17)
                    denied=await page.evaluate("async () => (await fetch('/healthz', {cache:'no-store',credentials:'same-origin'})).status")
                    jobs.require(denied==401,"已撤权 Cookie 仍能读取 IDE")
                    await observe("sleeping")
                    print(json.dumps({"code_server":"4.145.2","browser":"Chromium","saved_file_survived_restart":True,
                          "closed_on_revocation":len(active),"denied_http_status":denied,"observed":"sleeping",
                          "terminal_test":terminal_ok}),flush=True)
                    if opts.terminal:jobs.require(terminal_ok,"本轮浏览器终端测试缺少节点结果")
                finally:await browser.close()
            front.close();await front.wait_closed();front=None
            for task in tuple(tunnels):task.cancel()
            if tunnels:await asyncio.gather(*tuple(tunnels),return_exceptions=True)
    finally:
        if front:front.close();await front.wait_closed()
        if guard and guard.returncode is None:guard.terminate();await guard.wait()
        await tls.close()
        results=await asyncio.gather(worker.close(),central.close(),return_exceptions=True)
        for result in results:
            if isinstance(result,BaseException):raise result
        print("两机临时目录、工作区和附属服务已清理",flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("central","worker","browser-binary"):parser.add_argument("--"+name,required=True)
    for name in ("jar","guard","code-server"):parser.add_argument("--"+name,required=True,type=Path)
    parser.add_argument("--worker-archive",help="复用节点已有的官方归档绝对路径；夹具始终重新校验 SHA-256")
    parser.add_argument("--terminal",action="store_true",help="还要求通过浏览器集成终端运行工作区测试")
    try:
        asyncio.run(main(parser.parse_args()))
    except Exception as failure:
        # Browser-driver tracebacks may embed request headers. Keep fixture credentials out of reports.
        first=str(failure).splitlines()[0] if str(failure) else type(failure).__name__
        print("workspace acceptance failed:",first[:220],file=sys.stderr)
        sys.exit(1)
