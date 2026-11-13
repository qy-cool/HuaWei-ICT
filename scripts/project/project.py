"""新建订单后端项目、扫描已有项目生成导入草案、校验 Git 中的部署清单。"""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import zipfile
import tempfile
import xml.etree.ElementTree as ET

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas" / "cloide-v1alpha1.json").read_text(encoding="utf8"))
SKIP = {".git",".idea",".venv",".gradle",".artifacts","node_modules","target","dist","build","coverage"}

def slug(raw):
    result = re.sub("[^a-z0-9-]+", "-", raw.lower()).strip("-")
    return (result if re.match("[a-z]", result) else "app-" + result)[:63].rstrip("-")

def safe(root, path):
    relative = PurePosixPath(path)
    if relative.is_absolute() or "\\" in path or ":" in path or ".." in relative.parts:
        raise ValueError("路径须位于项目内部：" + path)
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("路径解析到项目之外：" + path)
    return target

def validate(root, manifest):
    jsonschema.Draft202012Validator(SCHEMA).validate(manifest)
    services = manifest["services"]
    order = manifest["release"]["order"]
    if set(order) != set(services):
        raise ValueError("发布顺序须包含每个服务一次")
    ready = set()
    for name in order:
        spec = services[name]
        if not ready.issuperset(spec.get("dependencies", [])):
            raise ValueError("依赖不存在、成环或执行顺序不正确：" + name)
        ready.add(name)
        if "source" in spec:
            source = safe(root, spec["source"])
            if not source.is_dir():
                raise ValueError("源码目录不存在：" + spec["source"])
            safe(source, spec["artifact"])

def template_copy(destination):
    if destination.exists():
        raise ValueError("新项目目录已存在")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as tmp:
        staged = Path(tmp) / "project"
        staged.mkdir()
        bundle = Path(tmp) / "template.zip"
        subprocess.run([sys.executable,str(ROOT/"scripts"/"export-order-template.py"),"--output",str(bundle)],check=True)
        with zipfile.ZipFile(bundle) as entries:
            for item in entries.infolist():
                dest = safe(staged,item.filename)
                dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_bytes(entries.read(item))
        manifest = yaml.safe_load((staged/"cloide.yaml").read_text(encoding="utf8"))
        manifest["project"] = slug(destination.name)
        validate(staged,manifest)
        (staged/"cloide.yaml").write_text(yaml.safe_dump(manifest,sort_keys=False),encoding="utf8")
        staged.rename(destination)

def inspect_repo(root):
    services = {}
    for folder, dirs, files in os.walk(root,followlinks=False):
        path = Path(folder)
        rel = path.relative_to(root)
        dirs[:] = sorted(d for d in dirs if d not in SKIP and not d.startswith(".") and
                         not (path/d).is_symlink() and len(rel.parts)<4)
        source = rel.as_posix()
        def add(suffix,spec):
            name = slug((path.name if rel.parts else root.name) + "-" + suffix)
            if name in services:
                raise ValueError("模块名称冲突，请手工编写清单：" + name)
            services[name] = {"source":source, **spec}
        if "pom.xml" in files:
            pom = ET.parse(safe(root,(rel/"pom.xml").as_posix())).getroot()
            def value(tag):
                return pom.findtext("{*}" + tag) or ""
            if value("packaging") in ("","jar") and any(el.text == "spring-boot" or (el.text or "").startswith("spring-boot-")
                    for el in pom.findall(".//{*}artifactId")):
                add("api",{"kind":"java-jar","build":["mvn","-B","package"],"test":["mvn","-B","test"],
                           "artifact":"target/" + value("artifactId") + "-" + (value("version") or "VERSION") + ".jar",
                           "health":"/actuator/health/readiness"})
        if "package.json" in files:
            package = json.loads(safe(root,(rel/"package.json").as_posix()).read_text(encoding="utf8"))
            if "react" in package.get("dependencies",{}) and "build" in package.get("scripts",{}):
                manager = "pnpm" if "pnpm-lock.yaml" in files else "npm"
                add("web",{"kind":"static-tar","build":[manager,"run","build"],"artifact":"dist","health":"/index.html"})
    if not services:
        raise ValueError("未识别受支持的模块，请指定源码目录并手工编写 cloide.yaml")
    return {"apiVersion":"cloide.dev/v1alpha1","project":slug(root.name),"services":services,
            "release":{"order":list(services),"migration":"manual-review","retainVersions":3}}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["new","inspect","validate"])
    parser.add_argument("directory",type=Path)
    parser.add_argument("--write",action="store_true",help="将经检查的草案写至尚不存在的 cloide.yaml")
    args=parser.parse_args()
    root = args.directory.resolve()
    if args.action == "new":
        template_copy(root)
        return
    if not root.is_dir():
        raise ValueError("请选择已检出的仓库目录")
    file = root/"cloide.yaml"
    if file.exists():
        manifest = yaml.safe_load(safe(root,"cloide.yaml").read_text(encoding="utf8"))
    elif args.action == "validate":
        raise ValueError("缺少 cloide.yaml")
    else:
        manifest = inspect_repo(root)
    validate(root,manifest)
    if args.action == "inspect":
        draft = yaml.safe_dump(manifest,sort_keys=False,allow_unicode=True)
        print(draft)
        if args.write:
            with file.open("x",encoding="utf8") as out:
                out.write(draft)
    else:
        print("清单验证成功，共",len(manifest["services"]),"个服务")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError, yaml.YAMLError, jsonschema.ValidationError) as error:
        raise SystemExit(str(error))
