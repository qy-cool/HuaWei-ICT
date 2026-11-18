"""Exercise the candidate Nginx site and legacy backup on private Linux loopback fixtures."""
from contextlib import contextmanager
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time


MIB = 1024 * 1024
JOB = "/api/build-agent/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/artifact"
PROJECT = "/api/projects/42/artifacts"


class Upstream(BaseHTTPRequestHandler):
    def do_POST(self):
        total = int(self.headers["Content-Length"])
        path = self.path.partition("?")[0]
        authenticated = ((path == PROJECT and self.headers.get("Authorization") == "Bearer fixture")
                         or (path == JOB and self.headers.get("X-Agent-Token") == "fixture"))
        if authenticated:
            remaining = total
            while remaining:
                chunk = self.rfile.read(min(remaining, 65536))
                if not chunk:
                    raise IOError("test upload interrupted")
                remaining -= len(chunk)
        self.send_response(200 if authenticated else 401)
        self.send_header("X-Received-Bytes", str(total) if authenticated else "0")
        self.end_headers()

    def log_message(self, *args):
        pass


@contextmanager
def upstream():
    with ThreadingHTTPServer(("127.0.0.1", 0), Upstream) as server:
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            thread.join()


def check_gateway(root, template):
    (root/"logs").mkdir()
    subprocess.run(["openssl","req","-x509","-nodes","-newkey","rsa:2048","-days","1",
                    "-subj","/CN=localhost","-addext","subjectAltName=IP:127.0.0.1",
                    "-keyout",str(root/"key"),"-out",str(root/"cert")],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    tls = ssl.create_default_context(cafile=root/"cert")
    with upstream() as app:
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1",0))
            port = reserve.getsockname()[1]
        site = template.read_text().replace("listen 80;",f"listen 127.0.0.1:{port} ssl;")
        site = site.replace("server_name SERVER_IP_OR_DOMAIN;",
                            f"server_name localhost; ssl_certificate {root}/cert; ssl_certificate_key {root}/key;")
        site = site.replace("127.0.0.1:18080",f"127.0.0.1:{app}")
        libdir = os.environ.get("CLOIDE_TEST_NGINX_LUA")
        if libdir:
            lua = Path(libdir).resolve(strict=True)
            if not (lua/"resty/core.lua").is_file() or "'" in str(lua):
                raise ValueError("invalid Nginx Lua library directory")
            site = f"lua_package_path '{lua}/?.lua;{lua}/?/init.lua;;';\n" + site
        conf = root/"nginx.conf"
        conf.write_text(f"daemon off; master_process off; pid {root}/nginx.pid; error_log {root}/error.log; "
                        f"events {{}} http {{access_log off; client_body_temp_path {root}/body; "
                        f"proxy_temp_path {root}/proxy; fastcgi_temp_path {root}/fcgi; "
                        f"uwsgi_temp_path {root}/uwsgi; scgi_temp_path {root}/scgi; {site}\n}}")
        cmd = ["nginx","-p",str(root),"-c",str(conf)]
        subprocess.run([*cmd,"-t"],check=True)
        nginx = subprocess.Popen(cmd)
        def req(method, path, length=0, headers=None, send=True):
            conn = http.client.HTTPSConnection("127.0.0.1",port,context=tls,timeout=15)
            try:
                conn.putrequest(method,path)
                conn.putheader("Content-Length",str(length))
                for name, value in (headers or {}).items():
                    conn.putheader(name,value)
                conn.endheaders()
                chunk = bytes(65536)
                remaining = length if send else 0
                while remaining:
                    n = min(remaining,len(chunk))
                    conn.send(chunk[:n])
                    remaining -= n
                result = conn.getresponse()
                return result.status, result.getheader("X-Received-Bytes"), result.read()
            finally:
                conn.close()
        try:
            for _ in range(100):
                if nginx.poll() is not None:
                    raise RuntimeError("Nginx exited")
                try:
                    if req("GET","/")[0] == 404:
                        break
                except OSError:
                    time.sleep(.05)
            else:
                raise RuntimeError("candidate API gateway did not start")
            assert req("GET","/projects/42")[0] == 404
            assert req("GET","/api/workspace-gateway/check")[0] == 404
            for path, auth, size in ((PROJECT,{"Authorization":"Bearer fixture"},17*MIB),
                                      (JOB,{"X-Agent-Token":"fixture"},128*MIB)):
                assert req("POST",path)[0] == 401
                assert req("POST",path+"?test=1",size,auth)[:2] == (200,str(size))
                assert req("POST",path,128*MIB+1,send=False)[0] == 413
            assert req("POST","/api/projects/42/runs",16*MIB+1,send=False)[0] == 413
        finally:
            nginx.terminate()
            nginx.wait(timeout=15)


def check_backup(root, script):
    stub = root/"mysqldump"
    stub.write_text("#!/bin/sh\necho 'SELECT 1;'\n")
    stub.chmod(0o700)
    envfile = root/"server.env"
    envfile.write_text("DB_PASSWORD=fixture\n")
    archive = root/"backup files"
    env = {**os.environ,"ENV_FILE":str(envfile),"BACKUP_DIR":str(archive),"PATH":str(root)+":"+os.environ["PATH"]}
    subprocess.run(["bash",str(script)],env=env,umask=0o022,check=True,stdout=subprocess.DEVNULL)
    files = list(archive.iterdir())
    assert stat.S_IMODE(archive.stat().st_mode) == 0o700
    assert len(files)==1 and stat.S_IMODE(files[0].stat().st_mode) == 0o600
    assert subprocess.check_output(["tar","-xOzf",str(files[0]),"./cloide.sql"]) == b"SELECT 1;\n"
    stub.write_text("#!/bin/sh\nexit 2\n")
    assert subprocess.run(["bash",str(script)],env=env,stdout=subprocess.DEVNULL).returncode!=0
    assert list(archive.iterdir()) == files


if __name__ == "__main__":
    directory = Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).resolve().parents[2]/"deploy/server"
    with tempfile.TemporaryDirectory(prefix="cloide-gateway-test-") as tmp:
        root = Path(tmp)
        check_gateway(root, directory/"nginx.conf.example")
        check_backup(root, directory/"backup.sh")
    print("PASS: HTTPS API gateway, upload limits and credential forwarding, private atomic backup; fixtures removed")
