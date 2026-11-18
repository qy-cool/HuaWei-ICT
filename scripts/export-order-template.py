#!/usr/bin/env python3
"""从工作区或已提交版本导出独立的订单后端 GitHub 仓库模板。"""
import argparse
from fnmatch import fnmatchcase
from pathlib import Path, PurePosixPath
import subprocess
import zipfile


PATTERNS = (
    ".gitignore", "README.md", "cloide.yaml", "verify_runtime.py", "verify_java_release.py",
    "api/pom.xml", "api/src/**/*.java", "api/src/main/resources/application.properties",
    "api/src/main/resources/db/migration/*.sql",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--ref", help="从本地提交或标签导出；默认使用工作区模板")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    def git(*argv):
        return subprocess.check_output(["git","-C",str(root),*argv])
    files = {}
    source = "working tree"
    if args.ref:
        source = git("rev-parse","--verify","--end-of-options",args.ref+"^{commit}").decode().strip()
        for entry in filter(None,git("ls-tree","-rz",source+":examples/orders").split(b"\0")):
            meta,name = entry.split(b"\t",1)
            mode,kind,oid = meta.split()
            path = name.decode("utf-8")
            if path == "web/package.json":
                parser.error("该历史版本的清单使用旧前端模板，请选择包含订单后端模板的提交")
            if not any(fnmatchcase(path, pattern) for pattern in PATTERNS):
                continue
            if kind != b"blob" or mode not in (b"100644",b"100755") or ".." in PurePosixPath(path).parts:
                parser.error("模板需要普通文件")
            files[path]=git("cat-file","blob",oid.decode())
        files["LICENSE"]=git("show",source+":LICENSE")
    else:
        directory=(root/"examples/orders").resolve(strict=True)
        for pattern in PATTERNS:
            for path in directory.glob(pattern):
                path.resolve(strict=True).relative_to(directory)
                if path.is_file() and not path.is_symlink():
                    files[path.relative_to(directory).as_posix()]=path.read_bytes()
        files["LICENSE"]=(root/"LICENSE").read_bytes()
    if not {"cloide.yaml","api/pom.xml","verify_runtime.py","verify_java_release.py"}.issubset(files):
        parser.error("缺少订单后端模板或业务验收脚本")
    with zipfile.ZipFile(args.output,"x",compression=zipfile.ZIP_DEFLATED) as archive:
        for path,content in sorted(files.items()):
            info=zipfile.ZipInfo(path,date_time=(1980,1,1,0,0,0))
            archive.writestr(info,content,compress_type=zipfile.ZIP_DEFLATED)
    print(f"{len(files)} files from {source}")


if __name__ == "__main__":
    main()
