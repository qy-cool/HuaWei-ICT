"""供两机端到端夹具使用的 HTTPS 转发、一次性签名材料和静态站点发布断言。"""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import io
import ipaddress
import json
from pathlib import Path
import ssl
import tarfile
import tempfile
import uuid

from aiohttp import web
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID


class ReleaseChecks:
    def __init__(self):
        self.temp = tempfile.TemporaryDirectory()
        self.tmp = Path(self.temp.name)
        self.runner = None
        self.drop_id = None
        self.receipts = []
        pem = serialization.Encoding.PEM
        self.signer = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.public = self.signer.public_key().public_bytes(pem, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.signatures = {}
        tls = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Cloide ephemeral smoke endpoint")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(tls.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5))
                .not_valid_after(now+timedelta(hours=2)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True)
                .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
                .sign(tls,hashes.SHA256()))
        self.ca = cert.public_bytes(pem).decode()
        key = self.tmp / "key.pem"
        key.write_bytes(tls.private_bytes(pem,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        cert_path = self.tmp / "cert.pem"
        cert_path.write_text(self.ca)
        self.ssl = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ssl.load_cert_chain(cert_path, key)

    async def listen(self, client, local, tls_port, route_prefix="/api/release-agent"):
        self.origin = f"http://127.0.0.1:{local}/api"
        async def forward(req):
            headers = {k: req.headers[k] for k in ("X-Agent-Token","Content-Type") if k in req.headers}
            async with client.request(req.method, "http://127.0.0.1:" + str(local) + req.path_qs,
                                      data=await req.read(), headers=headers) as r:
                content = await r.read()
                if req.path.endswith("/receipt") and r.status == 200:
                    self.receipts.append(req.path)
                    if self.drop_id is None:
                        self.drop_id = req.path
                        return web.Response(status=503)
                return web.Response(status=r.status, body=content, headers={"Content-Type":r.headers["Content-Type"]})
        app = web.Application()
        app.router.add_route("*", route_prefix+"/{path:.*}", forward)
        self.runner = web.AppRunner(app, access_log=None)
        await self.runner.setup()
        await web.TCPSite(self.runner,"127.0.0.1",tls_port,ssl_context=self.ssl).start()

    async def close(self):
        if self.runner:
            await self.runner.cleanup()
        self.temp.cleanup()

    async def exercise(self, client, api, token, project, test, run_id, worker, remote_port, cmd, ssh, check,built):
        invite = await api("POST","/admin/node-enrollments",{"name":"smoke-production"})
        prod = await api("POST","/node-enrollments/redeem",{"ticket":invite["ticket"]})
        await api("PUT",f"/projects/{project}/environments/prod/nodes/{prod['server_id']}")
        async def upload(page):
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode="w") as tar:
                body = page.encode()
                info = tarfile.TarInfo("index.html")
                info.size = len(body)
                tar.addfile(info, io.BytesIO(body))
            blob = data.getvalue()
            digest = hashlib.sha256(blob).hexdigest()
            self.signatures[digest] = base64.b64encode(self.signer.sign(blob,padding.PKCS1v15(),hashes.SHA256())).decode()
            headers = {"Authorization":"Bearer "+token,"Content-Type":"application/octet-stream","X-Content-SHA256":digest}
            async with client.post(self.origin+f"/projects/{project}/artifacts",params={"runId":run_id,"kind":"static-tar"},
                                   headers=headers,data=blob) as res:
                check(res.status==200, f"upload HTTP {res.status}")
                item = (await res.json())["data"]
            check(item["sha256"]==digest and item["byteCount"]==len(blob),"制品摘要或长度错误")
            return item
        async def move(env, identity, artifact, page, expected, undo=None):
            prefix = f"/projects/{project}/environments/{env}/releases"
            req = {"requestId":str(uuid.uuid4())}
            if undo:
                path = prefix + "/" + undo["id"] + "/rollback"
            else:
                path = prefix
                req.update({"artifactId":artifact["id"],"serverId":identity["server_id"],"service":"orders-site"})
            release = await api("POST",path,req)
            check(release["status"]=="queued" and (await api("POST",path,req))["id"]==release["id"],
                  "发布幂等性或队列状态异常")
            payload = {**identity,"project":project,"environment":env,"port":remote_port,"ca":self.ca,
                       "public":self.public,"signatures":self.signatures}
            raw = await cmd("ssh",*ssh,worker.host,f"exec python3 {worker.directory}/apply.py",
                            data=json.dumps(payload).encode(),timeout=90)
            result=json.loads(raw)
            check(result["id"]==release["id"] and result["acked"] and result["status"]==expected and result["page"]==page,
                  "活动页面、节点执行结果或回执不符")
            events = await api("GET",prefix+"/"+release["id"]+"/events")
            check([e["status"] for e in events]==["queued","applying",expected], "回执重复或缺少发布证据")
            return release
        a,blob=built
        check(a["sourceNodeId"]==test["server_id"] and a["sha256"]==hashlib.sha256(blob).hexdigest(),"首版未对应真实节点构建")
        self.signatures[a["sha256"]]=base64.b64encode(self.signer.sign(blob,padding.PKCS1v15(),hashes.SHA256())).decode()
        b,bad=await upload("orders-ready v2"), await upload("broken")
        check((await upload("orders-ready v2"))["id"]==b["id"],"同一制品重复上传导致新身份")
        await move("test",test,a,"orders-ready v1","healthy")
        check(self.drop_id and self.receipts.count(self.drop_id)==2,"节点未重放首次丢失的终态回执")
        await move("prod",prod,a,"orders-ready v1","healthy")
        improved = await move("test",test,b,"orders-ready v2","healthy")
        async with client.post(self.origin+f"/projects/{project}/environments/prod/releases",
                headers={"Authorization":"Bearer "+token},json={"requestId":str(uuid.uuid4()),"artifactId":b["id"],
                "serverId":prod["server_id"],"service":"orders-site"}) as rejected:
            check(rejected.status==409,"人工上传的测试健康版本被允许发布生产或产生非预期状态")
        await move("test",test,bad,"orders-ready v2","failed")
        await move("test",test,a,"orders-ready v1","healthy",undo=improved)
        print(json.dumps({"releases":5,"same_digest_promoted":True,"manual_prod_rejected":True,"lost_receipt_replayed":True,
                          "broken_probe_restored":"v2","explicit_rollback_restored":"v1",
                          "environments":2,"physical_workers":1}),flush=True)
