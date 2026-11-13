#!/usr/bin/env python3
"""固定单个企业工作区的低权限生命周期管理器。Linux、bubblewrap、Python 3.8+。"""
import argparse
import fcntl
import http.client
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import threading
import time
from urllib import error, parse, request
import uuid
from socket_relay import Relay, connect_ide


class BlockRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, message, headers, newurl):
        return None


class Control:
    def __init__(self, config):
        origin = parse.urlsplit(config["control"])
        if (origin.scheme != "https" or not origin.hostname or origin.path not in ("", "/")
                or origin.username or origin.password or origin.fragment or origin.query):
            raise ValueError("invalid HTTPS origin")
        self.workspace = str(uuid.UUID(config["workspace_id"]))
        self.project = int(config["project_id"])
        self.server = int(config["server_id"])
        self.hostname = config["hostname"]
        self.base = config["control"].rstrip("/") + "/api/workspace-agent/" + self.workspace + "/"
        self.token = Path(config["token_file"]).read_text().strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", self.token):
            raise ValueError("invalid node token")
        self.http = request.build_opener(request.ProxyHandler({}), BlockRedirect())

    def call(self, route, body=None):
        url = self.base + route + "?" + parse.urlencode({"serverId":self.server})
        req = request.Request(url, headers={"X-Agent-Token":self.token})
        if body is not None:
            req.data = json.dumps(body).encode()
            req.add_header("Content-Type","application/json")
        with self.http.open(req, timeout=5) as response:
            envelope=json.loads(response.read(16384))
        if envelope["code"] != 0:
            raise RuntimeError("control rejected request")
        return envelope["data"]

    def desired(self):
        row=self.call("runtime")
        if (row.get("id") != self.workspace or row.get("projectId") != self.project or row.get("serverId") != self.server
                or row.get("hostname") != self.hostname or row.get("desired") not in ("running","sleeping","observe")
                or type(row.get("generation")) is not int or row["generation"] < 1):
            raise ValueError("workspace target mismatch")
        return row["desired"],row["generation"]


class Instance:
    def __init__(self, config):
        self.root=Path(config["rootfs"]).resolve(strict=True)
        self.repo=Path(config["repository"]).resolve(strict=True)
        self.home=Path(config["ide_home"]).resolve(strict=True)
        self.runtime=Path(config["socket_directory"]).resolve(strict=True)
        self.private_proc=config.get("proc_mode","private")
        if self.private_proc not in ("private","empty"):
            raise ValueError("invalid proc mode")
        # The isolated process may write only these three explicitly dedicated directories.
        paths=[self.root,self.repo,self.home,self.runtime]
        token=Path(config["token_file"]).resolve(strict=True)
        self.gateway=Path(config["gateway_socket"])
        private=self.gateway.parent.resolve(strict=True)
        if (any(not p.is_dir() or p == Path("/") for p in paths)
                or any(p==q or p in q.parents for i,p in enumerate(paths) for j,q in enumerate(paths) if i!=j)
                or any(p==token or p in token.parents or p==private or p in private.parents for p in paths)):
            raise ValueError("workspace directories and management credentials must be isolated")
        self.proc=None

    def start(self):
        path=self.runtime/"ide.sock"
        if path.exists():
            if not path.is_socket():
                raise ValueError("invalid workspace socket")
            path.unlink()
        proc_mount=["--proc","/proc"] if self.private_proc=="private" else ["--tmpfs","/proc","--remount-ro","/proc"]
        args=["bwrap","--unshare-all","--new-session","--die-with-parent","--cap-drop","ALL","--clearenv",
              "--ro-bind",str(self.root),"/","--bind",str(self.repo),"/work","--bind",str(self.home),"/home/ide",
              "--bind",str(self.runtime),"/run","--dev","/dev",*proc_mount,"--size","268435456","--tmpfs","/tmp",
              "--chdir","/work","--setenv","HOME","/home/ide","--setenv","SHELL","/bin/sh","--setenv","LANG","C.UTF-8",
              "--setenv","PATH","/usr/bin:/bin","--","/opt/code-server/lib/node","/opt/code-server",
              "--socket","/run/ide.sock","--auth","none","--disable-proxy","--disable-update-check",
              "--disable-telemetry","--user-data-dir","/home/ide/data","--extensions-dir","/home/ide/extensions","/work"]
        self.proc=subprocess.Popen(args,start_new_session=True,stdin=subprocess.DEVNULL,
                                   env={"PATH":"/usr/bin:/bin","LANG":"C.UTF-8"})

    def ready(self):
        if self.proc is None or self.proc.poll() is not None:
            return False
        try:
            with connect_ide(self.runtime/"ide.sock") as channel:
                channel.settimeout(1)
                channel.sendall(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                response=http.client.HTTPResponse(channel)
                response.begin()
                return response.status == 200
        except (OSError,http.client.HTTPException):
            return False

    def stop(self):
        if self.proc is not None and self.proc.poll() is None:
            os.killpg(self.proc.pid,signal.SIGTERM)
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid,signal.SIGKILL)
                self.proc.wait(timeout=5)
        self.proc=None


def supervise(config):
    control=Control(config)
    instance=Instance(config)
    relay=Relay(instance.gateway,instance.runtime/"ide.sock")
    stop=threading.Event()
    for sig in (signal.SIGTERM,signal.SIGINT):
        signal.signal(sig,lambda *_:stop.set())
    attempted=None
    last_contact=time.monotonic()
    try:
        while not stop.is_set():
            try:
                desired,generation=control.desired()
                last_contact=time.monotonic()
                if desired=="sleeping":
                    instance.stop()
                    attempted=None
                    state="sleeping"
                elif desired=="observe":
                    state="running" if instance.ready() else "sleeping"
                else:
                    if attempted!=generation:
                        instance.stop()
                        attempted=generation
                        try:
                            instance.start()
                        except (OSError,ValueError):
                            instance.stop()
                        if instance.proc is not None:
                            for _ in range(50):
                                if instance.ready() or instance.proc.poll() is not None or stop.wait(.2):
                                    break
                    state="running" if instance.ready() else "failed"
                control.call("observations",{"generation":generation,"state":state})
            except error.HTTPError as failure:
                if failure.code in (401,403,404):
                    instance.stop()
                    attempted=None
            except (OSError,ValueError,RuntimeError):
                pass
            if time.monotonic()-last_contact>45:
                instance.stop()
                attempted=None
            stop.wait(2)
    finally:
        instance.stop()
        relay.stop()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config",type=Path)
    args=parser.parse_args()
    if os.geteuid()==0:
        parser.error("use a dedicated non-root workspace manager")
    config=json.loads(args.config.read_text())
    # This private lock is never mounted into the IDE.
    with Path(config["lock_file"]).open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        supervise(config)


if __name__=="__main__":
    main()
