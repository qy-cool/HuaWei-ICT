"""Linux 临时实例，实际签名、静态探针、失败恢复以及终态回执重放。"""
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import threading
import unittest
import uuid
import node


class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass


class Controller:
    def __init__(self):
        self.task = None
        self.payload = b""
        self.downloads = 0
        self.receipts = []
        self.lose = False

    def open(self, route):
        self.downloads += 1
        return io.BytesIO(self.payload)

    def json(self, route, query=None, body=None):
        if route == "next":
            return self.task
        self.receipts.append(body.copy())
        if self.lose:
            self.lose = False
            raise ConnectionError("回执丢失")
        self.task = None


def archive(name, contents):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as bundle:
        entry = tarfile.TarInfo(name)
        entry.size = len(contents)
        bundle.addfile(entry, io.BytesIO(contents))
    return out.getvalue()


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.central = Controller()
        self.generation = 0
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(self.root / "active")))
        thread = threading.Thread(target=httpd.serve_forever)
        thread.start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(httpd.shutdown)
        self.config = {"server_id": 2, "project_id": 1, "environment": "test", "service": "orders",
            "kind": "static-tar", "signatures": str(self.root), "public_key": str(self.root / "verify.pem"),
            "active_probe": "http://127.0.0.1:" + str(httpd.server_port) + "/index.html", "probe_contains": "orders-ready"}
        self.key = self.root / "key"
        node.run("openssl", "genrsa", "-out", str(self.key), "2048")
        node.run("openssl", "rsa", "-in", str(self.key), "-pubout", "-out", self.config["public_key"])

    def enqueue(self, payload):
        self.central.payload = payload
        sha = hashlib.sha256(payload).hexdigest()
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(self.key), "-out", str(self.root / (sha + ".sig"))],
                       input=payload, check=True)
        self.generation += 1
        self.central.task = {"id": str(uuid.uuid4()), "projectId": 1, "serverId": 2, "environment": "test",
                             "service": "orders", "sha256": sha, "kind": "static-tar", "generation": self.generation}

    def run_node(self):
        node.execute(self.config, self.central, self.root)

    def test_health_switch_and_recovery(self):
        self.enqueue(archive("index.html", b"orders-ready-v1"))
        self.central.lose = True
        with self.assertRaises(ConnectionError):
            self.run_node()
        self.run_node()
        self.assertEqual(self.central.downloads, 1)
        self.assertEqual(self.central.receipts[0], self.central.receipts[1])
        self.assertEqual(self.central.receipts[-1]["status"], "healthy")
        original = os.readlink(self.root / "active")
        self.enqueue(archive("index.html", b"unhealthy"))
        self.run_node()
        self.assertEqual(self.central.receipts[-1]["status"], "failed")
        self.assertEqual(os.readlink(self.root / "active"), original)
        self.assertEqual((self.root / "active" / "index.html").read_text(), "orders-ready-v1")

    def test_corruption_unsafe_archive_and_scope(self):
        self.enqueue(archive("index.html", b"orders-ready"))
        self.central.payload += b"corrupt"
        self.run_node()
        self.assertEqual(self.central.receipts[-1]["status"], "failed")
        self.enqueue(archive("../outside.txt", b"bad"))
        self.run_node()
        self.assertEqual(self.central.receipts[-1]["status"], "failed")
        self.assertFalse((self.root / "active").exists())
        self.enqueue(archive("index.html", b"orders-ready"))
        self.central.task["projectId"] = 15
        with self.assertRaises(ValueError):
            self.run_node()
        self.assertEqual(self.central.downloads, 2)

    def test_interrupted_effect_requires_reconciliation(self):
        self.enqueue(archive("index.html", b"orders-ready"))
        task = self.central.task.copy()
        node.save(self.root / "release.json", {"id":task["id"], "sha":task["sha256"], "generation":1})
        self.run_node()
        self.assertEqual(self.central.downloads, 0)
        self.assertEqual(self.central.receipts[-1]["status"], "uncertain")


if __name__ == "__main__":
    unittest.main()
