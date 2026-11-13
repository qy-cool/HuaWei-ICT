#!/usr/bin/env python3
"""双机验收的临时 Linux 夹具，仅由 run-project-jobs-e2e.py 在专用目录调用。"""
import base64
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import select
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request


def emit(value):
    print(json.dumps(value), flush=True)


def loopback_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def stop(proc):
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=5)


def wait_port(port, proc, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("temporary service exited with code " + str(proc.returncode))
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(0.3)
    raise RuntimeError("temporary service startup timed out")


def wait_java(port, proc, log):
    try:
        wait_port(port, proc, 65)
    except RuntimeError as error:
        # Only expose exception types, bean names and SQL state; startup logs may include configuration.
        text = log.read_text(errors="replace")[-18000:]
        causes = re.findall(r"Caused by: ([a-zA-Z0-9_.$]+)",text)
        beans = re.findall(r"bean with name '([a-zA-Z0-9_.$/-]+)'",text)
        states = re.findall(r"SQL State\s*:\s*([0-9A-Z]+)",text)
        flags = [phrase for phrase in ("Invalid or corrupt jarfile","Unable to access jarfile","requires a Java Runtime",
                 "no main manifest attribute","Port already in use","Checksum mismatch") if phrase in text]
        raise RuntimeError(f"{error}; causes={causes[-3:]} beans={beans[-3:]} sql={states[-1:]} flags={flags}") from error


def as_user(path, command, user="nobody"):
    account = pwd.getpwnam(user)
    path.mkdir(exist_ok=True)
    os.chown(path, account.pw_uid, account.pw_gid)
    return ["setpriv", "--reuid", str(account.pw_uid), "--regid", str(account.pw_gid),
            "--clear-groups", "--"] + command


def central(base, port, processes, workspace=None, standby_port=None):
    db = base / "db"
    data = db / "data"
    account = pwd.getpwnam("mysql")
    db.mkdir(mode=0o700)
    os.chown(db, account.pw_uid, account.pw_gid)
    mysql_port = loopback_port()
    init = db / "init.sql"
    password = secrets.token_hex(24)
    init.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + secrets.token_hex(24) + "';\n"
                    "CREATE DATABASE job_smoke CHARACTER SET utf8mb4;\n"
                    "CREATE USER 'smoke'@'127.0.0.1' IDENTIFIED BY '" + password + "';\n"
                    "GRANT ALL ON job_smoke.* TO 'smoke'@'127.0.0.1';\n")
    os.chown(init, account.pw_uid, account.pw_gid)
    init.chmod(0o600)
    opts = ["mysqld", "--no-defaults", "--user=mysql", "--datadir=" + str(data),
            "--log-error=" + str(db / "db.log"), "--skip-log-bin", "--innodb-buffer-pool-size=64M"]
    subprocess.run(opts + ["--initialize-insecure"], check=True, timeout=90,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    mysql = subprocess.Popen(opts + ["--bind-address=127.0.0.1", "--port=" + str(mysql_port),
                             "--socket=" + str(db / "mysql.sock"), "--pid-file=" + str(db / "mysql.pid"),
                             "--tmpdir=" + str(db), "--secure-file-priv=NULL", "--mysqlx=0",
                             "--performance-schema=OFF", "--init-file=" + str(init)],
                             start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    processes.append(mysql)
    wait_port(mysql_port, mysql, 45)
    # mysqld can bind TCP before it has opened --init-file. Wait until the new
    # database and account actually work before removing the one-time SQL file.
    probe = ["mysql", "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=" + str(mysql_port),
             "--connect-timeout=2", "--user=smoke", "--database=job_smoke", "--batch", "--execute=SELECT 1"]
    mysql_env = {"PATH": "/usr/bin:/bin", "MYSQL_PWD": password}
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if mysql.poll() is not None:
            errors = re.findall(r"\[(MY-\d+)\]",(db / "db.log").read_text(errors="replace"))[-5:]
            raise RuntimeError(f"temporary MySQL exited during SQL readiness: {mysql.returncode}, {errors}")
        if subprocess.run(probe,env=mysql_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=4).returncode == 0:
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("temporary database did not authenticate its fixture user")
    init.unlink()
    java_dir = base / "java"
    args = as_user(java_dir, ["java", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                              "-Djava.io.tmpdir=" + str(java_dir), "-jar", str(base / "server.jar")])
    bootstrap = secrets.token_hex(24)
    env = {"PATH": "/usr/sbin:/usr/bin:/bin", "HOME": str(java_dir), "LANG": "C.UTF-8",
           "JWT_SECRET": base64.b64encode(os.urandom(32)).decode(),
           "AES_GCM_KEY": base64.b64encode(os.urandom(32)).decode(), "AUTH_BOOTSTRAP_TOKEN": bootstrap,
           "DB_HOST": "127.0.0.1", "DB_PORT": str(mysql_port), "DB_NAME": "job_smoke", "DB_USER": "smoke",
           "DB_PASSWORD": password, "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(port), "APP_ENV": "prod",
           "OUTBOX_ENABLED": "false", "REDIS_ENABLED": "false",
           "CLOIDE_ARTIFACTS_DIRECTORY": str(java_dir / "artifacts")}
    if workspace:
        env.update({"WORKSPACE_DOMAIN":"ide.example.test","WORKSPACE_CONSOLE_ORIGIN":"https://console.example.test",
                    "WORKSPACE_GATEWAY_KEY":workspace["gateway_key"],"PROJECT_ISOLATION_ENABLED":"true"})
    if standby_port is not None:
        if not isinstance(standby_port, int) or not 1024 <= standby_port <= 65535 or standby_port == port:
            raise ValueError("invalid second control port")
        env["ENGINEERING_CLUSTER_CONNECTIONS"] = "true"
    with (base / "java.log").open("wb") as output:
        java = subprocess.Popen(args, cwd=java_dir, env=env, stdout=output, stderr=output, start_new_session=True)
    processes.append(java)
    wait_java(port, java, base / "java.log")
    # TCP may be listening before the bootstrap ApplicationRunner seeds the admin ticket.
    for _ in range(80):
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/api/auth/bootstrap-status" % port, timeout=1) as reply:
                if json.load(reply)["data"]["bootstrapPending"]:
                    break
        except (OSError, ValueError, KeyError):
            pass
        if java.poll() is not None:
            raise RuntimeError("temporary Java process exited before bootstrap")
        time.sleep(0.25)
    else:
        raise RuntimeError("bootstrap status timed out")
    migrated = subprocess.check_output([
        "mysql", "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=" + str(mysql_port),
        "--user=smoke", "--database=job_smoke", "--batch", "--skip-column-names",
        "--execute=SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1"],
        env={"PATH": "/usr/bin:/bin", "MYSQL_PWD": password}, text=True).strip()
    emit({"ready": True, "bootstrap": bootstrap, "flyway": migrated,
          "mysql_version": subprocess.check_output(["mysqld", "--version"], text=True).strip()})
    if standby_port is not None:
        def command(expected):
            if not select.select([sys.stdin], [], [], 100)[0]:
                raise RuntimeError("control fixture transition timed out")
            value = sys.stdin.readline()
            if not value:
                return False
            if value.strip() != expected:
                raise ValueError("unexpected fixture control action")
            return True

        def ownership():
            rows = subprocess.check_output([
                "mysql", "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=" + str(mysql_port),
                "--user=smoke", "--database=job_smoke", "--batch", "--skip-column-names",
                "--execute=SELECT server_id,instance_id,session_id FROM node_connection_owners"],
                env={"PATH": "/usr/bin:/bin", "MYSQL_PWD": password}, text=True, timeout=5).strip().splitlines()
            if len(rows) != 1:
                raise RuntimeError("expected one durable node owner")
            node, instance, session = rows[0].split("\t")
            return {"node":int(node), "instance":instance, "session":session}

        if not command("switch"):
            return
        previous = ownership()
        # The parent owns this exact test JVM. SIGKILL leaves its DB claim in place.
        java.kill()
        if java.wait(timeout=10) != -signal.SIGKILL:
            raise RuntimeError("first test JVM did not exit by SIGKILL")
        env["SERVER_PORT"] = str(standby_port)
        with (base / "second-java.log").open("wb") as output:
            replacement = subprocess.Popen(args,cwd=java_dir,env=env,stdout=output,stderr=output,start_new_session=True)
        processes.append(replacement)
        wait_java(standby_port,replacement,base / "second-java.log")
        if ownership() != previous:
            raise RuntimeError("old DB owner disappeared before reconnection")
        emit({"previous":previous, "distinct_process": replacement.pid != java.pid, "killed":True})
        if command("owner"):
            emit({"current":ownership()})


def worker(base, port, processes):
    config = json.loads(sys.stdin.readline())
    if not isinstance(config["project_id"], int) or not isinstance(config["server_id"], int):
        raise ValueError("invalid fixture identities")
    root = base / "rootfs"
    for subdir in ("tmp", "work", "objects", "dev", "proc"):
        (root / subdir).mkdir(parents=True)
    capture = config.get("capture_build") is True
    binaries = ["/bin/sh", "/usr/bin/git", "/usr/bin/od"]
    if capture:
        binaries.append("/usr/bin/tar")
    for binary in binaries:
        all_paths = re.findall(r"/[^\s()]+", subprocess.check_output(["ldd", binary], text=True))
        for source in [binary, *all_paths]:
            dest = root / source.lstrip("/")
            dest.parent.mkdir(exist_ok=True, parents=True)
            shutil.copy(source, dest)
            dest.chmod(0o755)
    source = base / "source"
    source.mkdir()
    git_env = {"PATH": "/usr/bin:/bin", "HOME": str(base), "GIT_CONFIG_NOSYSTEM": "1"}
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=source, env=git_env, text=True).strip()
    git("init", "-q")
    (source / "verify.sh").write_text(
        "set -eu\ntest -f verify.sh\ntest -z \"\x24{DB_PASSWORD:-}\"\ntest -z \"\x24{CLOIDE_AGENT_TOKEN:-}\"\n"
        "test ! -e /etc/shadow\nprintf 'orders-check-passed:'\nod -An -tx1 -N16 /dev/urandom\n" +
        ('tar --format=ustar -cf "$CLOIDE_BUILD_OUTPUT" index.html\n' if capture else ""))
    git("add", "verify.sh")
    if capture:
        (source / "index.html").write_text("orders-ready v1")
        git("add","index.html")
    git("-c", "user.email=fixture@example.invalid", "-c", "user.name=Fixture", "commit", "-qm", "known test")
    sha = git("rev-parse", "HEAD")
    bare = base / "orders.git"
    git("clone", "-q", "--bare", "--no-local", str(source), str(bare))
    state = base / "state"
    manifest = base / "manifest.json"
    repo = {"project_id": config["project_id"], "name":"orders",
            "bare_git_dir":str(bare), "test_argv":["/bin/sh","verify.sh"]}
    registration = {"state_directory":str(state),"toolchain_root":str(root),"timeout_seconds":30,"repositories":[repo]}
    if capture:
        repo["artifact_kind"]="static-tar"
        registration["toolchains"]=["git","tar"]
        account=pwd.getpwnam("nobody")
        with (base / "publisher-token").open("x") as secret_file:
            os.fchmod(secret_file.fileno(),0o400)
            os.fchown(secret_file.fileno(),account.pw_uid,account.pw_gid)
            secret_file.write(config["agent_token"])
    manifest.write_text(json.dumps(registration))
    manifest.chmod(0o644)
    args = as_user(state, [str(base / "agent")])
    env = {"PATH": "/usr/bin:/bin", "HOME": str(state), "LANG": "C.UTF-8",
           "CLOIDE_BACKEND_URL": "ws://127.0.0.1:%d" % port, "CLOIDE_SERVER_ID": str(config["server_id"]),
           "CLOIDE_AGENT_TOKEN": config["agent_token"], "CLOIDE_JOB_MANIFEST": str(manifest),
           "CLOIDE_RECONNECT_INITIAL_SECONDS": "1", "CLOIDE_RECONNECT_MAX_SECONDS": "2",
           "CLOIDE_METRICS_BUFFER_PATH": str(state / "metrics.json")}
    with (base / "agent.log").open("wb") as output:
        proc = subprocess.Popen(args, cwd=state, env=env, stdout=output, stderr=output, start_new_session=True)
    processes.append(proc)
    account = pwd.getpwnam("nobody")
    emit({"ready": True, "commit_sha": sha, "execution_uid":account.pw_uid,"execution_gid":account.pw_gid})


def main():
    # The orchestrator creates this directory with mktemp; only its children may be removed.
    role, port = sys.argv[1], int(sys.argv[2])
    base = Path(__file__).resolve().parent
    if base.parent != Path("/tmp") or not re.fullmatch(r"cloide-job-smoke\.[A-Za-z0-9]{8}", base.name):
        raise ValueError("unexpected fixture directory")
    processes = []
    try:
        base.chmod(0o711)
        if role == "central":
            central(base, port, processes)
        elif role == "failover-central":
            central(base,port,processes,standby_port=json.loads(sys.stdin.readline())["second_port"])
        elif role == "ide-central":
            central(base,port,processes,json.loads(sys.stdin.readline()))
        elif role == "worker":
            worker(base, port, processes)
        else:
            raise ValueError("unknown role")
        # EOF on SSH disconnect, a stop line, or the hard lifetime each ends the fixture.
        select.select([sys.stdin], [], [], 240)
    finally:
        for process in reversed(processes):
            stop(process)
        shutil.rmtree(base)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("fixture error: " + str(error), file=sys.stderr)
        sys.exit(1)
