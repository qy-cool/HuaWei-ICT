#!/usr/bin/env python3
"""将本机已完成构建的内容和作业来源上传，收到确认再从待发送队列移出。"""
import argparse
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import ssl
from urllib.parse import urlencode, urlsplit
import uuid

def transmit(config, token, record, blob):
    site = urlsplit(config.origin)
    if not site.hostname or site.path not in ("","/") or site.query or site.fragment or site.username or site.password:
        raise ValueError("需要中控 HTTPS 源站")
    if site.scheme == "https":
        link = http.client.HTTPSConnection(site.hostname,site.port,timeout=45,context=ssl.create_default_context())
    elif config.ssh_loopback and site.scheme == "http" and site.hostname in ("127.0.0.1","::1"):
        link = http.client.HTTPConnection(site.hostname,site.port,timeout=45)
    else:
        raise ValueError("仅允许 HTTPS 或显式指定的 SSH 本地回环转发")
    job = str(uuid.UUID(record["job_id"]))
    if (job != blob.parent.name or not re.fullmatch("[a-f0-9]{40}",record["commit_sha"])
            or record["kind"] not in ("java-jar","static-tar") or blob.is_symlink()):
        raise ValueError("本机作业证明无效")
    with blob.open("rb") as contents:
        size=os.fstat(contents.fileno()).st_size
        if size<=0 or size>128*1024**2: raise ValueError("制品大小无效")
        digest=hashlib.sha256()
        while chunk:=contents.read(65536):
            digest.update(chunk)
        if digest.hexdigest()!=record["sha256"]: raise ValueError("制品内容已改变")
        contents.seek(0)
        query=urlencode({"serverId":config.server_id,"projectId":record["project_id"],"commit":record["commit_sha"],"kind":record["kind"]})
        try:
            link.request("POST","/api/build-agent/"+job+"/artifact?"+query,body=contents,headers={
                "Content-Type":"application/octet-stream","Content-Length":str(size),"X-Agent-Token":token,"X-Content-SHA256":record["sha256"]})
            response=link.getresponse()
            if response.status!=200: raise RuntimeError("中控尚未确认上传，HTTP " + str(response.status))
            reply=json.loads(response.read(65536))
        finally:
            link.close()
    result=reply["data"] if reply["code"]==0 else None
    if not result or result["sha256"]!=record["sha256"] or result["runId"]!=job or result["sourceNodeId"]!=config.server_id:
        raise RuntimeError("返回的制品来源与作业不符")
    return result["id"]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin",required=True)
    parser.add_argument("--server-id",type=int,required=True)
    parser.add_argument("--token-file",type=Path,required=True)
    parser.add_argument("--state",type=Path,required=True)
    parser.add_argument("--ssh-loopback",action="store_true")
    config=parser.parse_args()
    token=config.token_file.read_text().strip()
    if not re.fullmatch("[a-zA-Z0-9_-]{43}",token) or config.server_id<=0: parser.error("节点身份无效")
    state=config.state.resolve(strict=True)
    with (state/"artifact-upload.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        errors=0
        for pending in sorted((state/"artifacts").glob("*/pending.json")):
            try:
                if pending.is_symlink() or pending.parent.is_symlink(): raise ValueError("账本路径无效")
                ident=transmit(config,token,json.loads(pending.read_text()),pending.parent/"blob")
                pending.rename(pending.with_name("uploaded.json"))
                fd=os.open(pending.parent,os.O_RDONLY|os.O_DIRECTORY)
                try: os.fsync(fd)
                finally: os.close(fd)
                print("已登记构建制品",ident)
            except (OSError,ValueError,RuntimeError,KeyError,http.client.HTTPException) as error:
                errors+=1
                print("保留待重试作业",pending.parent.name,type(error).__name__)
        return int(errors>0)
if __name__=="__main__":
    raise SystemExit(main())
