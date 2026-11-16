#!/usr/bin/env python3
"""Exchange an enrollment ticket and write a new private agent environment file."""
import argparse
import getpass
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def enroll(endpoint, output, ticket):
    url = urllib.parse.urlsplit(endpoint)
    if (url.scheme != "https" or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ("", "/")
            or not re.fullmatch(r"[A-Za-z0-9.\-:\[\]]+", url.netloc)):
        raise ValueError("中控地址须为 HTTPS 源站")
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", ticket):
        raise ValueError("登记令牌格式错误")
    if output.exists():
        raise ValueError("目标配置文件已存在")
    with tempfile.NamedTemporaryFile(mode="w", dir=output.parent, delete=False) as handle:
        temp = Path(handle.name)
        try:
            request = urllib.request.Request(
                endpoint.rstrip("/") + "/api/node-enrollments/redeem",
                data=json.dumps({"ticket": ticket}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=15) as response:
                data = json.load(response)["data"]
            node_id, token = data["server_id"], data["agent_token"]
            if type(node_id) is not int or node_id < 1 or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
                raise ValueError("中控返回了无效的身份数据")
            handle.write(f"CLOIDE_BACKEND_URL=wss://{url.netloc}\nCLOIDE_SERVER_ID={node_id}\n"
                         f"CLOIDE_AGENT_TOKEN={token}\n")
            handle.flush()
            os.fsync(handle.fileno())
            os.link(temp, output)
            return node_id
        finally:
            temp.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("endpoint")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        value = getpass.getpass("节点登记令牌：") if sys.stdin.isatty() else sys.stdin.readline().strip()
        print("已登记节点", enroll(args.endpoint, args.output, value))
    except (ValueError, OSError, KeyError, TypeError) as exc:
        sys.exit(f"节点登记失败：{exc}")
