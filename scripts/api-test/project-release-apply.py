#!/usr/bin/env python3
"""在双机临时目录中用正式节点执行器执行一次发布，验收后关闭本地 HTTP 业务探针。"""
import base64
from functools import partial
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys
import threading


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass


def main():
    base = Path(__file__).resolve().parent
    if base.parent != Path("/tmp") or not re.fullmatch(r"cloide-job-smoke\.[A-Za-z0-9]{8}", base.name):
        raise ValueError("unexpected fixture directory")
    info = json.load(sys.stdin)
    env = info["environment"]
    if env not in ("test", "prod"):
        raise ValueError("unknown environment")
    uid = pwd.getpwnam("nobody").pw_uid
    gid = pwd.getpwnam("nobody").pw_gid
    root = base / ("release-" + env)
    root.mkdir(exist_ok=True, mode=0o711)
    state = root / "state"
    state.mkdir(exist_ok=True)
    os.chown(state, uid, gid)
    token = root / "token"
    token.write_text(info["agent_token"])
    token.chmod(0o400)
    os.chown(token, uid, gid)
    (root / "ca.pem").write_text(info["ca"])
    (root / "pub.pem").write_text(info["public"])
    for sha, sig in info["signatures"].items():
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise ValueError("invalid digest")
        (root / (sha + ".sig")).write_bytes(base64.b64decode(sig, validate=True))
    handler = partial(QuietHandler, directory=str(state / "active"))
    probe = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=probe.serve_forever, daemon=True)
    thread.start()
    try:
        config = {"control": "https://127.0.0.1:" + str(info["port"]), "token_file": str(token),
                  "service": "orders-site", "project_id": info["project"], "server_id": info["server_id"],
                  "environment": env, "state": str(state), "kind": "static-tar", "signatures": str(root),
                  "public_key": str(root / "pub.pem"), "active_probe": "http://127.0.0.1:%d/index.html" % probe.server_port,
                  "probe_contains": "orders-ready"}
        config_file = root / "node.json"
        config_file.write_text(json.dumps(config))
        args = ["setpriv", "--reuid", str(uid), "--regid", str(gid), "--clear-groups", "--",
                "python3", str(base / "release-node.py"), str(config_file)]
        proc_env = {"PATH": "/usr/bin:/bin", "HOME": str(state), "SSL_CERT_FILE": str(root / "ca.pem")}
        for attempt in range(2):
            result = subprocess.run(args, env=proc_env, capture_output=True, timeout=65)
            if result.returncode == 0:
                break
            if attempt or "HTTP Error 503" not in result.stderr.decode(errors="replace"):
                lines = result.stderr.decode(errors="replace").splitlines()
                raise RuntimeError("release executor exited: " + (lines[-1][:120] if lines else str(result.returncode)))
        record = json.loads((state / "release.json").read_text())
        active = (state / "active/index.html").read_text()
        print(json.dumps({"acked": record.get("acked"), "id": record["id"], "status": record["receipt"]["status"],
                          "page": active, "generation": record["generation"],
                          "versions": len(list((state / "versions").iterdir()))}))
    finally:
        probe.shutdown()
        thread.join(3)
        probe.server_close()


if __name__ == "__main__":
    main()
