"""从固定 Git 提交生成真实 tar；丢弃第一次成功的上传回执，回读制品来源和内容。"""
import hashlib
import io
import json
import tarfile
from aiohttp import web


class BuildCheck:
    def __init__(self,client,local_port):
        self.client=client
        self.local_port=local_port
        self.uploaded=[]
        self.response_status=None

    async def forward(self,req):
        blob=await req.read()
        headers={name:req.headers[name] for name in ("Content-Type","X-Agent-Token","X-Content-SHA256")}
        target=f"http://127.0.0.1:{self.local_port()}"+req.path_qs
        async with self.client.post(target,data=blob,headers=headers) as res:
            raw=await res.read()
            self.response_status=res.status
            if res.status==200:
                item=json.loads(raw)["data"]
                if item["sha256"]!=hashlib.sha256(blob).hexdigest():
                    raise RuntimeError("上传摘要与收到的节点内容不符")
                self.uploaded.append(item)
                if len(self.uploaded)==1:
                    return web.Response(status=503)
            return web.Response(status=res.status,body=raw,content_type="application/json")

    async def verify(self,api,token,project,node,job,worker_info,worker,remote_port,cmd,ssh,check):
        caps=await api("GET",f"/projects/{project}/nodes/{node}/capabilities")
        check(caps["os"]=="linux" and caps["arch"]=="arm64" and caps["cpus"]>0 and
              caps["repositories"]==["orders"] and {"git","tar"}.issubset(caps["toolchains"]),
              "节点能力报告与项目、工具链不符")
        uid,gid=int(worker_info["execution_uid"]),int(worker_info["execution_gid"])
        check(uid>0 and gid>0,"上传器应使用夹具的非特权账户")
        publish=(f"exec setpriv --reuid {uid} --regid {gid} --clear-groups -- python3 "
                 f"{worker.directory}/publish-build.py --origin http://127.0.0.1:{remote_port} --ssh-loopback "
                 f"--state {worker.directory}/state --server-id {int(node)} --token-file {worker.directory}/publisher-token")
        try:
            await cmd("ssh",*ssh,worker.host,publish,timeout=60)
        except RuntimeError as error:
            if len(self.uploaded)!=1:
                raise RuntimeError(f"节点上传未获确认，转发 HTTP 状态 {self.response_status}，{error}") from error
        if len(self.uploaded)==1:
            await cmd("ssh",*ssh,worker.host,publish,timeout=60)
        check(len(self.uploaded)==2 and self.uploaded[0]["id"]==self.uploaded[1]["id"],
              "制品首次回执丢失后出现重复身份或缺少重送")
        item=self.uploaded[-1]
        check(item["sourceNodeId"]==node and item["runId"]==job and item["projectId"]==project and
              item["commitSha"]==worker_info["commit_sha"] and item["repository"]=="orders" and item["kind"]=="static-tar",
              "制品的记录来源、节点或基准提交不符")
        catalog=await api("GET",f"/projects/{project}/artifacts")
        check(len(catalog)==1 and catalog[0]["id"]==item["id"],"项目制品目录出现重复记录")
        url=f"http://127.0.0.1:{self.local_port()}/api/projects/{project}/artifacts/{item['id']}/content"
        async with self.client.get(url,headers={"Authorization":"Bearer "+token}) as response:
            check(response.status==200,"从项目制品库下载失败")
            content=await response.read()
        check(hashlib.sha256(content).hexdigest()==item["sha256"] and len(content)==item["byteCount"],
              "回读制品与摘要或长度不一致")
        with tarfile.open(fileobj=io.BytesIO(content),mode="r:") as archive:
            check(archive.getnames()==["index.html"] and archive.extractfile("index.html").read()==b"orders-ready v1",
                  "制品未包含固定提交的参考页面")
        print(json.dumps({"capture_build":True,"platform":caps["os"]+"/"+caps["arch"],"repository":"orders",
            "uploads":len(self.uploaded),"distinct_artifacts":len(catalog),"bytes":item["byteCount"],
            "source_node_verified":True,"sha256_verified":True}),flush=True)
        return item,content
