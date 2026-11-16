#!/usr/bin/env python3
"""Linux Docker + Java 21 上临时启动订单业务，实测 PostgreSQL、Redis、RabbitMQ 后自动清理。"""
import argparse
import base64
from contextlib import ExitStack
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib import request, error
import uuid

IMAGES = {
    "pg": "postgres@sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5",
    "redis": "redis@sha256:21090af93f8324db2f0f0f4f38a7c60369d47d917e000c3423d89d2ae2883537",
    "rabbit": "rabbitmq@sha256:e582c0bc7766f3342496d8485efb5a1df782b5ce3886ad017e2eaae442311f69",
}

def cmd(*args, env=None):
    return subprocess.run(args, stdin=subprocess.DEVNULL, check=True, env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=90).stdout.strip()

def wait(check, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        try:
            if check():
                return
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(.5)
    raise RuntimeError("等待业务条件超时：" + getattr(check, "__name__", "probe"))

def fetch(url, data=None, headers=None, method=None):
    hdr = {"Content-Type": "application/json", **(headers or {})}
    req = request.Request(url, headers=hdr, method=method,
                          data=json.dumps(data).encode() if data is not None else None)
    with request.urlopen(req, timeout=5) as response:
        return json.load(response)

def stop_process(proc):
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=12)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()

def verify(jar):
    with ExitStack() as stack:
        tmp = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="orders-regression-")))
        run_id = "orders-" + uuid.uuid4().hex[:12]
        password = secrets.token_urlsafe(24)
        def container(kind, ports, values):
            name = run_id + "-" + kind
            args = ["docker", "run", "--rm", "-d", "--pull=never", "--name", name]
            for port in ports:
                args.extend(["-p", "127.0.0.1::" + str(port)])
            for key in values:
                args.extend(["-e", key])
            cid = cmd(*args, IMAGES[kind], env={**os.environ, **values})
            stack.callback(cmd, "docker", "rm", "-f", "-v", cid)
            return name, [int(cmd("docker", "port", cid, str(p) + "/tcp").splitlines()[0].rsplit(":", 1)[1]) for p in ports]
        db, (pgport,) = container("pg", [5432], {"POSTGRES_DB":"orders","POSTGRES_USER":"orders","POSTGRES_PASSWORD":password})
        cache, (redisport,) = container("redis", [6379], {})
        broker, (amqp, management) = container("rabbit", [5672, 15672], {
            "RABBITMQ_DEFAULT_USER":"orders","RABBITMQ_DEFAULT_PASS":password,"RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS":"+S 1:1"})
        def sql(statement):
            return cmd("docker","exec",db,"psql","-X","-A","-t","-v","ON_ERROR_STOP=1","-U","orders","-d","orders","-c",statement)
        wait(lambda: sql("SELECT 1") == "1")
        admin = "http://127.0.0.1:" + str(management) + "/api/"
        headers = {"Authorization":"Basic " + base64.b64encode(("orders:" + password).encode()).decode()}
        wait(lambda: fetch(admin + "overview", headers=headers))
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0))
            port = sock.getsockname()[1]
        artifact = tmp / "app.jar"
        shutil.copyfile(jar, artifact)
        os.chmod(artifact, 0o444)
        os.chmod(tmp, 0o755)
        log = stack.enter_context((tmp / "app.log").open("wb"))
        java = ["java","-Xms48m","-Xmx192m","-XX:ActiveProcessorCount=2","-jar",str(artifact)]
        if os.geteuid() == 0:
            java = ["setpriv","--reuid=65534","--regid=65534","--clear-groups"] + java
        env = {"PATH":os.environ["PATH"],"LANG":"C.UTF-8","ORDERS_DB_URL":f"jdbc:postgresql://127.0.0.1:{pgport}/orders",
               "ORDERS_DB_USER":"orders","ORDERS_DB_PASSWORD":password,"ORDERS_REDIS_HOST":"127.0.0.1",
               "ORDERS_REDIS_PORT":str(redisport),"ORDERS_MQ_HOST":"127.0.0.1","ORDERS_MQ_PORT":str(amqp),
               "ORDERS_MQ_USER":"orders","ORDERS_MQ_PASSWORD":password,"ORDERS_PORT":str(port)}
        base = "http://127.0.0.1:" + str(port)
        proc = subprocess.Popen(java, env=env, cwd=tmp, stdin=subprocess.DEVNULL, stdout=log,
                                stderr=subprocess.STDOUT, start_new_session=True)
        stack.callback(stop_process, proc)
        def ready():
            if proc.poll() is not None:
                lines=(tmp / "app.log").read_text(errors="replace").splitlines()
                reason=next((line for line in reversed(lines) if "Caused by:" in line), "查看 JVM 和数据库配置")
                raise RuntimeError("订单 API 启动失败：" + reason.replace(password,"[redacted]")[:300])
            return fetch(base+"/actuator/health/readiness").get("status") == "UP"
        wait(ready)
        api = base + "/api/orders"
        before = fetch(api, {"item": "发布前订单"})
        target = api + "/" + before["id"]
        assert fetch(target)["status"] == "pending"
        key = "orders:" + before["id"] + ":1"
        assert cmd("docker","exec",cache,"redis-cli","exists",key) == "1"
        cancelled = fetch(target + "/cancel", method="POST")
        assert fetch(target + "/cancel", method="POST") == cancelled
        assert fetch(target)["status"] == "cancelled"
        after = fetch(api, {"item":"发布后订单"})
        shipped = api + "/" + after["id"]
        fetch(shipped + "/ship", method="POST")
        try:
            fetch(shipped + "/cancel", method="POST")
        except error.HTTPError as exc:
            assert exc.code == 409
        else:
            raise AssertionError("已发货订单被取消")
        wait(lambda: sql("SELECT COUNT(*) FROM order_event_receipts") == "4")
        event_id = sql("SELECT event_id FROM order_outbox WHERE status='cancelled'")
        duplicate = {"eventId":event_id,"orderId":before["id"],"orderVersion":2,"status":"cancelled"}
        message = {"properties":{"delivery_mode":2, "message_id":event_id},"routing_key":"orders.events",
                   "payload":json.dumps(duplicate),"payload_encoding":"string"}
        queue = admin + "queues/%2F/orders.events"
        # Rabbit 的管理统计按周期刷新；要求观察到重复消息的额外一次确认。
        wait(lambda: fetch(queue, headers=headers).get("message_stats",{}).get("ack",0) >= 4)
        assert fetch(admin + "exchanges/%2F/amq.default/publish",message,headers)["routed"]
        wait(lambda: fetch(queue, headers=headers).get("message_stats",{}).get("ack",0) >= 5)
        assert sql("SELECT COUNT(*) FROM order_event_receipts") == "4"
        assert sql("SELECT COUNT(*) FROM order_outbox WHERE sent") == "4"
        print("订单 2 笔，唯一事件及回执 4 个，重复投递已消费；缓存命中、重复取消和发货冲突验证完成。")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    verify(parser.parse_args().jar)
