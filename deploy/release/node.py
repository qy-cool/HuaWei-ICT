#!/usr/bin/env python3
"""Linux 受管发布执行器。每次处理一个管理员配置的项目、环境、服务。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
from urllib import parse, request
import uuid

LIMIT = 256 * 1024 * 1024


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


http = request.build_opener(request.ProxyHandler({}), NoRedirect())


def sync_dir(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def save(path, document):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as out:
        tmp = Path(out.name)
        try:
            json.dump(document, out)
            out.flush()
            os.fsync(out.fileno())
        finally:
            out.close()
    try:
        os.replace(tmp, path)
        sync_dir(path.parent)
    finally:
        tmp.unlink(missing_ok=True)


def switch(link, target):
    if target is None:
        link.unlink(missing_ok=True)
    else:
        temp = link.parent / ("link-" + uuid.uuid4().hex)
        try:
            temp.symlink_to(target, target_is_directory=True)
            os.replace(temp, link)
        finally:
            temp.unlink(missing_ok=True)
    sync_dir(link.parent)


def unpack(archive, directory):
    total = 0
    seen = set()
    with tarfile.open(archive, "r|*") as bundle:
        for member in bundle:
            rel = PurePosixPath(member.name)
            if (rel.is_absolute() or ".." in rel.parts or
                    not (member.isdir() or member.isreg()) or member.size < 0):
                raise ValueError("归档包含不受支持的路径或文件类型")
            total += member.size
            if total > LIMIT or len(seen) >= 20000 or rel in seen:
                raise ValueError("归档过大或包含重复路径")
            seen.add(rel)
            dest = directory.joinpath(*rel.parts)
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as src, dest.open("xb") as out:
                    shutil.copyfileobj(src, out, 65536)
                    out.flush()
                    os.fsync(out.fileno())
    if not (directory / "index.html").is_file():
        raise ValueError("静态站点缺少 index.html")


def run(*argv):
    subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=60, check=True)


def probe(url, marker):
    parsed = parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1"):
        raise ValueError("探针必须指向本机回环地址")
    for _ in range(12):
        try:
            with http.open(url, timeout=2) as response:
                if response.status == 200 and marker.encode() in response.read(65536):
                    return
        except OSError:
            pass
        time.sleep(1)
    raise RuntimeError("业务探针未就绪")


class Control:
    def __init__(self, config):
        origin = parse.urlsplit(config["control"])
        if origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.path not in ("", "/") or origin.query or origin.fragment:
            raise ValueError("中控地址必须为 HTTPS 源站")
        self.base = config["control"].rstrip("/") + "/api/release-agent/"
        secret = Path(config["token_file"]).read_text().strip()
        if not re.fullmatch(r"[a-zA-Z0-9_-]{43}", secret):
            raise ValueError("节点令牌格式错误")
        self.headers = {"X-Agent-Token": secret}
        self.node = config["server_id"]

    def open(self, route, query=None, body=None):
        query = parse.urlencode({"serverId": self.node, **(query or {})})
        req = request.Request(self.base + route + "?" + query, headers=self.headers)
        if body is not None:
            req.add_header("Content-Type", "application/json")
            req.data = json.dumps(body).encode()
        return http.open(req, timeout=30)

    def json(self, route, query=None, body=None):
        with self.open(route, query, body) as response:
            data = json.loads(response.read(32768))
        if data["code"] != 0:
            raise RuntimeError("中控未接受操作")
        return data["data"]


def prepare(config, control, task, state):
    sha = task["sha256"]
    destination = state / "versions" / (str(task["generation"]) + "-" + sha)
    versions = destination.parent
    versions.mkdir(exist_ok=True)
    if destination.exists():
        raise ValueError("版本目录已占用")
    with tempfile.TemporaryDirectory(dir=versions) as tmp:
        stage = Path(tmp)
        blob = stage / "artifact"
        digest = hashlib.sha256()
        size = 0
        with control.open(str(task["id"]) + "/content") as response, blob.open("xb") as out:
            while chunk := response.read(65536):
                size += len(chunk)
                if size > LIMIT:
                    raise ValueError("制品超出节点上限")
                digest.update(chunk)
                out.write(chunk)
            out.flush()
            os.fsync(out.fileno())
        if digest.hexdigest() != sha:
            raise ValueError("制品摘要与发布申请不匹配")
        signature = Path(config["signatures"]) / (sha + ".sig")
        run("openssl", "dgst", "-sha256", "-verify", config["public_key"],
            "-signature", str(signature), str(blob))
        content = stage / "content"
        content.mkdir()
        if task["kind"] == "static-tar":
            unpack(blob, content)
        else:
            os.replace(blob, content / "app.jar")
        for directory, _, _ in os.walk(content, topdown=False):
            sync_dir(directory)
        os.replace(content, destination)
        sync_dir(versions)
    return destination


def unit(config, op, slot):
    name = config[slot + "_unit"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9@_.-]{0,126}\.service", name):
        raise ValueError("服务单元名称无效")
    run("systemctl", "--user", op, name)


def deploy(config, control, task, state):
    active = state / "active"
    previous = os.readlink(active) if active.is_symlink() else None
    if active.exists() and previous is None:
        raise ValueError("活动版本必须使用执行器管理的符号链接")
    is_jar = task["kind"] == "java-jar"
    version = prepare(config, control, task, state)
    if is_jar:
        candidate = state / "candidate"
        switch(candidate, version)
        try:
            unit(config, "restart", "candidate")
            probe(config["candidate_probe"], config["probe_contains"])
        finally:
            unit(config, "stop", "candidate")
    try:
        switch(active, version)
        if is_jar:
            unit(config, "restart", "active")
        probe(config["active_probe"], config["probe_contains"])
    except Exception as error:
        try:
            switch(active, previous)
            if is_jar:
                unit(config, "restart" if previous else "stop", "active")
            if previous:
                probe(config["active_probe"], config["probe_contains"])
        except Exception:
            return "uncertain", "恢复上一版本时无法确认健康状态，请核对节点和业务探针"
        return "failed", "候选版本切换失败，已恢复发布前的目标；" + type(error).__name__
    return "healthy", "制品签名和摘要校验成功，活动版本业务探针通过"


def execute(config, control, state):
    journal = state / "release.json"
    record = json.loads(journal.read_text()) if journal.exists() else None
    if record and not record.get("acked"):
        if "receipt" not in record:
            record["receipt"] = {"sha256": record["sha"], "status": "uncertain",
                                 "evidence": "节点在记录终态前中断，请核对活动版本和探针"}
            save(journal, record)
        control.json(str(record["id"]) + "/receipt", body=record["receipt"])
        record["acked"] = True
        save(journal, record)
    selection = {"projectId": config["project_id"], "environment": config["environment"],
                 "service": config["service"]}
    task = control.json("next", selection)
    if not task:
        return
    if (any(task.get(k) != v for k, v in selection.items())
            or task.get("serverId") != config["server_id"]
            or type(task.get("generation")) is not int
            or not re.fullmatch(r"[a-f0-9]{64}", task.get("sha256", ""))
            or not re.fullmatch(r"[0-9a-fA-F-]{36}|[0-9]+", str(task.get("id", "")))):
        raise ValueError("作业与本地授权的项目、节点或服务不符")
    if record and record["id"] == task["id"]:
        if record["sha"] != task["sha256"] or record["generation"] != task["generation"]:
            raise ValueError("同一发布的身份或内容出现冲突")
        control.json(str(record["id"]) + "/receipt", body=record["receipt"])
        return
    if record and task["generation"] <= record["generation"]:
        raise ValueError("拒绝执行过期发布")
    record = {"id": task["id"], "sha": task["sha256"], "generation": task["generation"]}
    save(journal, record)
    try:
        if task["kind"] != config["kind"] or task["kind"] not in ("static-tar", "java-jar"):
            raise ValueError("本地服务不支持该制品类型")
        status, evidence = deploy(config, control, task, state)
    except (ValueError, OSError, subprocess.SubprocessError, RuntimeError, tarfile.TarError) as error:
        status, evidence = "failed", "版本预检或候选实例准备失败；" + type(error).__name__
    receipt = {"sha256": task["sha256"], "status": status, "evidence": evidence}
    record["receipt"] = receipt
    save(journal, record)
    control.json(str(task["id"]) + "/receipt", body=receipt)
    record["acked"] = True
    save(journal, record)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    if os.geteuid() == 0:
        parser.error("使用专用非 root 发布账户运行")
    config = json.loads(args.config.read_text())
    state = Path(config["state"]).resolve(strict=True)
    with (state / "release.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        execute(config, Control(config), state)


if __name__ == "__main__":
    main()
