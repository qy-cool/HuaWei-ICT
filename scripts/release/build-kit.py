#!/usr/bin/env python3
"""将 Java 中控、Linux 节点、编码 Worker 和订单后端示例组成按架构区分的候选发行物。"""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def source_files(*specs):
    output = subprocess.check_output(["git","-C",str(ROOT),"ls-files","-z","--cached","--others","--exclude-standard","--",*specs])
    return [Path(p.decode()) for p in output.split(b"\0") if p]

def copy_file(src, dest):
    if not src.is_file() or src.is_symlink():
        raise ValueError("缺少普通文件：" + str(src))
    dest.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(src,dest)

def is_notice(name):
    return bool(re.match(r"^(?:LICEN[CS]E|COPYING|NOTICE|THIRD-PARTY)(?:$|[.-])",Path(name).name,re.I))

def notices(stage, jar, arch):
    notice_dir = stage/"licenses"
    notice_dir.mkdir()
    missing = []
    with zipfile.ZipFile(jar) as spring:
        for name in sorted(spring.namelist()):
            if not (name.startswith("BOOT-INF/lib/") and name.endswith(".jar")):
                continue
            artifact = Path(name).name
            found = False
            with zipfile.ZipFile(io.BytesIO(spring.read(name))) as lib:
                for filename in lib.namelist():
                    if is_notice(filename) and not filename.endswith("/"):
                        out = notice_dir/"jvm"/artifact/Path(filename).name
                        data = lib.read(filename)
                        if out.exists() and out.read_bytes()!=data:
                            out = out.with_name(hashlib.sha256(data).hexdigest()[:12] + "-" + out.name)
                        out.parent.mkdir(parents=True,exist_ok=True)
                        out.write_bytes(data)
                        found = True
            if not found:
                missing.append(artifact)
    env = {**os.environ,"GOOS":"linux","GOARCH":arch,"CGO_ENABLED":"0"}
    fmt = '{{if .Module}}{{if not .Module.Main}}{{.Module.Path}}|{{.Module.Dir}}{{end}}{{end}}'
    modules = subprocess.check_output(["go","list","-deps","-f",fmt,"./cmd/cloide-agent"],cwd=ROOT/"agent",env=env,text=True)
    for row in sorted(set(modules.splitlines())-{""}):
        module, directory = row.split("|",1)
        files = [p for p in Path(directory).iterdir() if p.is_file() and is_notice(p.name)] if directory else []
        if not files:
            missing.append(module)
        for src in files:
            copy_file(src,notice_dir/"go"/module/src.name)
    (notice_dir/"audit.json").write_text(json.dumps({"withoutEmbeddedNotice":missing},indent=2)+"\n", encoding="utf8")

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--arch",choices=["amd64","arm64"],required=True)
    for item in ["agent","guard","output"]:
        p.add_argument("--"+item,type=Path,required=True)
    p.add_argument("--server-jar",type=Path,default=ROOT/"server/target/cloide-server-0.0.1-SNAPSHOT.jar")
    p.add_argument("--version",required=True)
    p.add_argument("--epoch",type=int,default=0)
    args=p.parse_args()
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?",args.version) or args.epoch<0:
        p.error("版本应为 v主.次.修订，可带候选后缀")
    prefix=f"cloide-{args.version}-linux-{args.arch}"
    stage=args.output/"stage"/prefix
    stage.mkdir(parents=True,exist_ok=False)
    jar=args.server_jar
    copy_file(jar,stage/"server/target/cloide-server-0.0.1-SNAPSHOT.jar")
    copy_file(args.agent,stage/"deploy/agent"/("cloide-agent-linux-"+args.arch))
    copy_file(args.guard,stage/"deploy/workspace/cloide-workspace-guard")
    for rel in source_files("deploy/agent","deploy/server","deploy/middleware","deploy/release","deploy/recovery","deploy/workspace",
            "worker/src","worker/acceptance","worker/test","worker/java-analysis/src","worker/java-analysis/pom.xml","worker/java-analysis/README.md",
            "worker/package.json","worker/package-lock.json","worker/README.md","docs/ops","docs/openapi","docs/ops-manual",
            "docs/plans/20261112*","docs/logs/20261112*","docs/protocol","docs/tech-summary",
            "scripts/api-test/*project*.py","scripts/api-test/*workspace*.py","scripts/api-test/README.md","scripts/release/verify-*.py",
            "LICENSE","SECURITY.md","CONTRIBUTING.md","README.md","schemas","scripts/project","scripts/export-order-template.py",
            "examples/orders/api","examples/orders/.gitignore","examples/orders/README.md","examples/orders/cloide.yaml",
            "examples/orders/verify_runtime.py","examples/orders/verify_java_release.py"):
        if rel.name in (".env",) or rel.suffix in {".pem",".key",".pyc"}:
            raise ValueError("发行输入出现本地运行材料：" + str(rel))
        copy_file(ROOT/rel,stage/rel)
    notices(stage,jar,args.arch)
    archive = args.output/(prefix+".tar.gz")
    with archive.open("xb") as target, gzip.GzipFile(filename="",mode="wb",fileobj=target,mtime=args.epoch) as gz:
        with tarfile.open(fileobj=gz,mode="w|") as tar:
            for file in sorted(stage.rglob("*")):
                if not file.is_file(): continue
                info=tarfile.TarInfo(prefix+"/"+file.relative_to(stage).as_posix())
                info.size=file.stat().st_size
                info.mode=0o755 if file.suffix in (".sh",".py") or file.name in ("cloide-agent-linux-"+args.arch,"cloide-workspace-guard") else 0o644
                info.mtime=args.epoch
                with file.open("rb") as src: tar.addfile(info,src)
    with archive.open("rb") as src: print(hashlib.file_digest(src,"sha256").hexdigest()+"  "+archive.name)
if __name__=="__main__":
    main()
