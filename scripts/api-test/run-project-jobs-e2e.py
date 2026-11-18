#!/usr/bin/env python3
"""在两台已授权 Linux 测试机上验证节点断线后重放同一结果。

运行机需要 Python 3.11+、aiohttp 和 OpenSSH。中控机需要独立 mysqld、Java 21、
Python 3.7+、setpriv 和 mysql 用户；开发节点需要 Linux ARM64、Git、bwrap、prlimit、
setpriv、Python 和 nobody 用户。SSH 测试账号须能启动上述隔离身份的测试实例。
参数传 SSH 别名、已构建的服务端 jar、对应架构的 Agent。两条 SSH 隧道均只绑定回环。
"""
import argparse
import asyncio
import json
import io
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tarfile
import uuid

import aiohttp
from aiohttp import web


SSH = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no",
       "-o", "ConnectTimeout=8", "-o", "LogLevel=ERROR", "-o", "ExitOnForwardFailure=yes"]
FIXTURE = Path(__file__).with_name("project-job-fixture.py")


async def cmd(*args, timeout=45, data=None):
    proc = await asyncio.create_subprocess_exec(*args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        out, err = await asyncio.wait_for(proc.communicate(data), timeout)
    except BaseException:
        if proc.returncode is None:
            proc.kill()
        await proc.wait()
        raise
    if proc.returncode:
        lines = err.decode(errors="replace").splitlines()
        raise RuntimeError(f"{args[0]} failed ({proc.returncode}): " + (lines[-1][:200] if lines else "no diagnostic"))
    return out.decode().strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class Fixture:
    def __init__(self, host):
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", host):
            raise ValueError("expected an SSH alias")
        self.host = host
        self.directory = None
        self.proc = None

    async def upload(self, artifact, name, extra=None):
        directory = await cmd("ssh", *SSH, self.host, "mktemp -d /tmp/cloide-job-smoke.XXXXXXXX")
        if not re.fullmatch(r"/tmp/cloide-job-smoke\.[A-Za-z0-9]{8}", directory):
            raise RuntimeError("unexpected mktemp response")
        self.directory = directory
        if extra:
            # Give the fixture and its small helper the same transport as the Agent.
            # Each archive member below is an explicit local input and a fixed basename.
            stream=io.BytesIO()
            with tarfile.open(fileobj=stream,mode="w:gz",compresslevel=3) as archive:
                for path,dest in [(FIXTURE,"fixture.py"),(artifact,name),*extra]:
                    if not re.fullmatch(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9]+)?",dest) or not path.is_file():
                        raise ValueError("invalid fixture input")
                    member=tarfile.TarInfo(dest)
                    member.size=path.stat().st_size
                    member.mode=0o755 if dest==name else 0o644
                    with path.open("rb") as source:
                        archive.addfile(member,source)
            await cmd("ssh",*SSH,self.host,f"tar -xzf - --no-same-owner -C {directory}",data=stream.getvalue(),timeout=360)
            return
        for path, destination in ((FIXTURE, "fixture.py"), (artifact, name)):
            await cmd("scp", "-q", "-O", *SSH, str(path), f"{self.host}:{self.directory}/{destination}", timeout=240)
        await cmd("ssh", *SSH, self.host, f"chmod 755 {self.directory}/{name}")

    async def start(self, role, port, forward, content=None):
        args = ["ssh", "-T", *SSH, *forward, self.host, f"exec python3 -u {self.directory}/fixture.py {role} {port}"]
        self.proc = await asyncio.create_subprocess_exec(*args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if content is not None:
            self.proc.stdin.write(json.dumps(content).encode() + b"\n")
            await self.proc.stdin.drain()
        reply = await asyncio.wait_for(self.proc.stdout.readline(), 180)
        if not reply:
            err = (await self.proc.stderr.read()).decode(errors="replace").splitlines()
            raise RuntimeError(f"{role} startup failed: " + (err[-1][:200] if err else "no diagnostic"))
        return json.loads(reply)

    async def close(self):
        if not self.directory:
            return
        if self.proc:
            self.proc.stdin.close()
            try:
                await asyncio.wait_for(self.proc.wait(), 45)
            except asyncio.TimeoutError:
                self.proc.kill()
                await self.proc.wait()
                raise RuntimeError("SSH fixture did not finish cleanup")
        # The remote guardian removes its own tree after stopping both subprocesses.
        # When a file transfer failed before start, this tree only contains uploaded files.
        if self.proc is None:
            await cmd("ssh", *SSH, self.host, f"cd {self.directory} && rm -f -- fixture.py server.jar agent apply.py release-node.py workspace-node.py socket_relay.py code-server.tar.gz publish-build.py && rmdir -- {self.directory}")
        await cmd("ssh", *SSH, self.host, "test ! -e " + self.directory)


class LostAck:
    def __init__(self, client, port, failover=False):
        self.client = client
        self.url = f"http://127.0.0.1:{port}/ws/agent"
        self.connections = 0
        self.dropped_on = None
        self.confirmed_on = None
        self.acked = asyncio.Event()
        self.results = []
        self.log_text = ""
        self.failover = failover
        self.dropped = asyncio.Event()
        self.route_ready = asyncio.Event()
        self.route_ready.set()

    async def bridge(self, request):
        if not self.route_ready.is_set():
            return web.Response(status=503, headers={"Retry-After":"1"})
        downstream = web.WebSocketResponse(max_msg_size=65536)
        await downstream.prepare(request)
        self.connections += 1
        connection = self.connections
        async with self.client.ws_connect(self.url, max_msg_size=65536) as upstream:
            async def copy(src, dest, from_node):
                async for item in src:
                    if item.type != aiohttp.WSMsgType.TEXT:
                        break
                    frame = json.loads(item.data)
                    kind, body = frame.get("type"), frame.get("payload", {})
                    if from_node and kind == "job.log":
                        self.log_text += body["text"]
                    if from_node and kind == "job.result":
                        self.results.append(body)
                    if not from_node and kind == "job.result.ack":
                        if self.dropped_on is None:
                            self.dropped_on = connection
                            if self.failover:
                                self.route_ready.clear()
                                self.dropped.set()
                                # Keep the live session open until the first JVM is terminated.
                                await self.route_ready.wait()
                            break
                        self.confirmed_on = connection
                        self.acked.set()
                    await dest.send_str(item.data)
            traffic = [asyncio.create_task(copy(downstream, upstream, True)), asyncio.create_task(copy(upstream, downstream, False))]
            try:
                await asyncio.wait(traffic, return_when=asyncio.FIRST_COMPLETED)
            finally:
                for task in traffic:
                    task.cancel()
                await asyncio.gather(*traffic, return_exceptions=True)
                await downstream.close()
        return downstream


async def exercise(args):
    central, worker = Fixture(args.central), Fixture(args.worker)
    local, remote_api, remote_ws = free_port(), 40000 + secrets.randbelow(15000), 40000 + secrets.randbelow(15000)
    standby_local = free_port()
    while standby_local == local:
        standby_local = free_port()
    standby_remote = 55000 + secrets.randbelow(8000)
    runner = None
    loss = None
    release = None
    print("正在传送临时夹具与构建产物", flush=True)
    try:
        await central.upload(args.jar, "server.jar")
        helpers=[(FIXTURE.parents[2] / "deploy/agent/publish-build.py","publish-build.py")] if args.capture_build else []
        if args.releases:
            from project_release_checks import ReleaseChecks
            release = ReleaseChecks()
            helpers.extend([(FIXTURE.with_name("project-release-apply.py"),"apply.py"),
                            (FIXTURE.parents[2] / "deploy/release/node.py","release-node.py")])
        await worker.upload(args.agent, "agent",helpers)
        central_forwards = ["-L", f"127.0.0.1:{local}:127.0.0.1:{remote_api}"]
        if args.failover:
            central_forwards.extend(["-L",f"127.0.0.1:{standby_local}:127.0.0.1:{standby_remote}"])
        reply = await central.start("failover-central" if args.failover else "central", remote_api, central_forwards,
                                    {"second_port":standby_remote} if args.failover else None)
        print("独立中控就绪", reply["mysql_version"], "Flyway V" + reply["flyway"], flush=True)
        async with aiohttp.ClientSession(trust_env=False, timeout=aiohttp.ClientTimeout(total=15)) as client:
            token = None
            async def api(method, path, body=None):
                headers = {"Authorization": "Bearer " + token} if token else {}
                async with client.request(method, f"http://127.0.0.1:{local}/api{path}", json=body, headers=headers) as res:
                    data = await res.json()
                    if res.status != 200 or data["code"] != 0:
                        raise RuntimeError(f"{method} {path} HTTP {res.status} business code {data.get('code')}")
                    return data["data"]
            login = {"username": "smoke_admin", "password": secrets.token_urlsafe(24)}
            loss = LostAck(client, local,args.failover)
            app = web.Application()
            app.router.add_get("/ws/agent", loss.bridge)
            if args.capture_build:
                from project_build_checks import BuildCheck
                build = BuildCheck(client,lambda:local)
                app.router.add_post("/api/build-agent/{jobId}/artifact",build.forward)
            runner = web.AppRunner(app, access_log=None)
            await runner.setup()
            proxy = free_port()
            await web.TCPSite(runner, "127.0.0.1", proxy).start()
            await api("POST", "/auth/register", {**login, "bootstrapToken": reply["bootstrap"]})
            token = (await api("POST", "/auth/login", login))["token"]
            project = (await api("POST", "/projects", {"name": "job-e2e"}))["id"]
            invite = await api("POST", "/admin/node-enrollments", {"name":"smoke-node"})
            identity = await api("POST", "/node-enrollments/redeem", {"ticket":invite["ticket"]})
            async with client.post(f"http://127.0.0.1:{local}/api/node-enrollments/redeem",
                                   json={"ticket": invite["ticket"]}) as replay:
                require((await replay.json())["code"] != 0, "已兑换的邀请仍可使用")
            server = identity["server_id"]
            await api("PUT", f"/projects/{project}/environments/test/nodes/{server}")
            forwards = ["-R", f"127.0.0.1:{remote_ws}:127.0.0.1:{proxy}"]
            if release:
                tls_port = free_port()
                remote_tls = 55000 + secrets.randbelow(8000)
                await release.listen(client, standby_local if args.failover else local, tls_port)
                forwards += ["-R",f"127.0.0.1:{remote_tls}:127.0.0.1:{tls_port}"]
            worker_info = await worker.start("worker", remote_ws, forwards,
                        {**identity, "project_id":project, "capture_build":args.capture_build})
            prefix = f"/projects/{project}/runs"
            payload = {"request_id":str(uuid.uuid4()), "repository":"orders", "commit_sha":worker_info["commit_sha"], "server_id":server}
            initial = await api("POST", prefix, payload)
            duplicate = await api("POST", prefix, payload)
            require(initial["job_id"] == duplicate["job_id"], "重复 API 请求产生了第二项作业")
            print("真实作业已登记，等待首次终态回执丢失后的重连", flush=True)
            async def fixture_action(action):
                central.proc.stdin.write((action+"\n").encode())
                await central.proc.stdin.drain()
                line = await asyncio.wait_for(central.proc.stdout.readline(), 80)
                if not line:
                    raise RuntimeError("temporary control transition failed")
                return json.loads(line)
            if args.failover:
                await asyncio.wait_for(loss.dropped.wait(),90)
                switch = await fixture_action("switch")
                require(switch["killed"] and switch["distinct_process"], "未确认原中控终止及新进程")
                local = standby_local
                loss.url = f"http://127.0.0.1:{local}/ws/agent"
                loss.route_ready.set()
            await asyncio.wait_for(loss.acked.wait(), 90)
            if args.failover:
                current = (await fixture_action("owner"))["current"]
                previous = switch["previous"]
                require(current["node"] == previous["node"] == server and current["instance"] != previous["instance"]
                        and current["session"] != previous["session"],"节点连接未由另一中控实例接管")
                print("中控接管已核实：首个 JVM 被强制终止，同一数据库中的实例与节点会话归属均已更新",flush=True)
            detail = await api("GET", prefix + "/" + initial["job_id"])
            logs = await api("GET", prefix + "/" + initial["job_id"] + "/logs")
            if detail["status"] != "succeeded":
                raise RuntimeError("fixture test failed: " + str(detail.get("output"))[:300])
            require(loss.confirmed_on != loss.dropped_on, "终态回执没有经过新的节点连接")
            require(len(loss.results) >= 2 and all(r == loss.results[0] for r in loss.results), "重放的终态结果不一致")
            require(loss.log_text.count("orders-check-passed:") == 1, "测试运行标记出现次数异常")
            require(re.search(r"orders-check-passed:(?: [0-9a-f]{2}){16}", detail["output"]), "测试未生成执行标记")
            require(detail["output"] == loss.results[0]["output"] and detail["exit_code"] == 0, "中控报告与节点结果不同")
            require(sum(line["text"].count("orders-check-passed:") for line in logs) == 1, "持久日志缺失或重复")
            require(len(await api("GET", prefix)) == 1, "项目历史中出现额外运行")
            if args.capture_build:
                built=await build.verify(api,token,project,server,initial["job_id"],worker_info,
                                         worker,remote_ws,cmd,SSH,require)
            print(json.dumps({"nodes":2, "failover":args.failover, "runs":1, "ack_dropped":loss.dropped_on is not None, "connections":loss.connections,
                "result_deliveries":len(loss.results), "distinct_execution_markers":1,
                "log_chunks":len(logs), "status":detail["status"], "exit_code":detail["exit_code"]}), flush=True)
            if release:
                await release.exercise(client, api, token, project, identity, initial["job_id"], worker,
                                       remote_tls, cmd, SSH, require,built)
    finally:
        if loss:
            loss.route_ready.set()
        cleanups = [worker.close(), central.close()]
        if release:
            cleanups.append(release.close())
        results = await asyncio.gather(*cleanups, return_exceptions=True)
        if runner:
            await runner.cleanup()
        for result in results:
            if isinstance(result, BaseException):
                raise result
        print("两个独立夹具的退出与临时目录清理均已核实", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("central", "worker"):
        parser.add_argument("--" + name, required=True)
    for name in ("jar", "agent"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--releases",action="store_true", help="用签名静态包验证发布、提升和回滚，运行机需 cryptography")
    parser.add_argument("--failover",action="store_true", help="丢回执后强制终止首个临时 JVM，核验另一 JVM 接管真实 Agent 和作业")
    parser.add_argument("--capture-build",action="store_true", help="验证节点能力、制品来源及上传回执丢失后的幂等恢复")
    opts = parser.parse_args()
    opts.capture_build = opts.capture_build or opts.releases
    if not opts.jar.is_file() or not opts.agent.is_file():
        parser.error("请提供已构建的服务端 JAR 与目标架构 Agent")
    asyncio.run(exercise(opts))
