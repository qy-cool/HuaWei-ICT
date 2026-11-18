#!/usr/bin/env python3
"""在独立 ARM 临时目录准备经 SHA 验证的真实 code-server 和最小只读工具链。"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import select
import shutil
import signal
import subprocess
import sys


def main():
    base=Path(__file__).resolve().parent
    if base.parent!=Path("/tmp") or not re.fullmatch(r"cloide-job-smoke\.[A-Za-z0-9]{8}",base.name):
        raise ValueError("unexpected fixture root")
    process=None
    try:
        cfg=json.loads(sys.stdin.readline())
        base.chmod(0o711)
        tar=base/"code-server.tar.gz"
        digest=hashlib.sha256()
        with tar.open("rb") as stream:
            while True:
                data=stream.read(1024*1024)
                if not data: break
                digest.update(data)
        if digest.hexdigest()!="e867a34416e2da4a83496b09580001893e7a4e5bb58a0624c4fcd22819e7d53d":
            raise ValueError("code-server archive SHA256 mismatch")
        root=base/"rootfs"
        for p in ("opt/code-server","tmp","run","dev","proc","work","home/ide","etc"):
            (root/p).mkdir(parents=True,exist_ok=True)
        subprocess.run(["tar","-xzf",str(tar),"-C",str(root/"opt/code-server"),"--strip-components=1","--no-same-owner"],check=True,timeout=130)
        for binary in (str(root/"opt/code-server/lib/node"),str(root/"opt/code-server/lib/vscode/node_modules/node-pty/build/Release/pty.node"),
                       "/bin/sh","/usr/bin/git","/usr/bin/env","/bin/cat","/usr/bin/id"):
            libs=re.findall(r"/[^\s()]+",subprocess.check_output(["ldd",binary],text=True))
            for original in libs+([] if binary.startswith(str(root)) else [binary]):
                dest=root/original.lstrip("/")
                dest.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy(original,dest)
        who=pwd.getpwnam("nobody")
        (root/"etc/passwd").write_text("nobody:x:%d:%d:Workspace:/home/ide:/bin/sh\n"%(who.pw_uid,who.pw_gid))
        (root/"etc/group").write_text("nobody:x:%d:\n"%who.pw_gid)
        dirs={"repository":"repo","ide_home":"home","socket_directory":"socket","private":"private"}
        for k,v in dirs.items():
            path=base/v
            path.mkdir(mode=0o700)
            os.chown(path,who.pw_uid,who.pw_gid)
        as_user=["setpriv","--reuid",str(who.pw_uid),"--regid",str(who.pw_gid),"--clear-groups","--"]
        env={"PATH":"/usr/bin:/bin","HOME":str(base/"home"),"GIT_CONFIG_NOSYSTEM":"1","LANG":"C.UTF-8"}
        def git(*args):
            subprocess.run(as_user+["git","-C",str(base/"repo"),*args],env=env,stdout=subprocess.DEVNULL,check=True)
        git("init","-q")
        file=base/"repo"/"acceptance.txt"
        file.write_text("initial-workspace\n")
        os.chown(file,who.pw_uid,who.pw_gid)
        test=base/"repo"/"verify.sh"
        test.write_text("set -eu\ntest \"$(cat acceptance.txt)\" = workspace-persistence-check\n"
                        "test -z \"\x24{CLOIDE_AGENT_TOKEN:-}\"\ntest -z \"\x24{DB_PASSWORD:-}\"\n"
                        "printf workspace-ide-test-ok > ide-test-result.txt\n")
        os.chown(test,who.pw_uid,who.pw_gid)
        tasks=base/"repo/.vscode/tasks.json"
        tasks.parent.mkdir()
        tasks.write_text(json.dumps({"version":"2.0.0","tasks":[{"label":"Verify saved workspace","type":"process",
                         "command":"/bin/sh","args":["verify.sh"],"problemMatcher":[],"group":{"kind":"test","isDefault":True}}]}))
        os.chown(tasks.parent,who.pw_uid,who.pw_gid)
        os.chown(tasks,who.pw_uid,who.pw_gid)
        git("add","acceptance.txt","verify.sh",".vscode/tasks.json")
        git("-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","fixture")
        token=base/"private/token"
        token.write_text(cfg.pop("agent_token"));token.chmod(0o400);os.chown(token,who.pw_uid,who.pw_gid)
        ca=base/"ca.pem";ca.write_text(cfg.pop("ca"))
        cfg.update({k:str(base/v) for k,v in dirs.items() if k!="private"})
        cfg.update({"rootfs":str(root),"token_file":str(token),"proc_mode":"empty","lock_file":str(base/"private/lock"),
                    "gateway_socket":str(base/"private/gateway.sock")})
        file=base/"config.json";file.write_text(json.dumps(cfg));file.chmod(0o644)
        env["SSL_CERT_FILE"]=str(ca)
        source_dir=base/"supervisor"
        source_dir.mkdir(mode=0o755)
        for name in ("workspace-node.py","socket_relay.py"):
            target=source_dir/name
            shutil.copyfile(base/name,target)
            target.chmod(0o644)
        with (base/"manager.log").open("wb") as log:
            process=subprocess.Popen(as_user+["python3","-B","-u",str(source_dir/"workspace-node.py"),str(file)],
                                     stdin=subprocess.DEVNULL,stdout=log,stderr=log,env=env,start_new_session=True)
        print(json.dumps({"ready":True}),flush=True)
        select.select([sys.stdin],[],[],480)
    finally:
        if process and process.poll() is None:
            os.killpg(process.pid,signal.SIGTERM)
            try: process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
        shutil.rmtree(base)


if __name__=="__main__":
    main()
