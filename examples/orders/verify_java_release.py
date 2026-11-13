#!/usr/bin/env python3
"""在专用测试 Linux 上用真实 systemd 用户单元、签名 JAR 和独立中间件验收订单回滚。"""
import argparse
import base64
from contextlib import ExitStack, contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import signal
import socket
import ssl
import stat
import subprocess
import tempfile
import threading
from urllib.parse import urlsplit
import uuid
import zipfile

from verify_runtime import IMAGES, cmd, fetch, wait


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1",0))
        return s.getsockname()[1]


@contextmanager
def account(root):
    name="cloidet"+secrets.token_hex(5)
    home=root/"home"
    home.mkdir(mode=0o700)
    cmd("useradd","--no-create-home","--user-group","--home-dir",str(home),
        "--shell","/usr/sbin/nologin",name)
    user=pwd.getpwnam(name)
    uid,gid=user.pw_uid,user.pw_gid
    manager=f"user@{uid}.service"
    runtime=f"/run/user/{uid}"
    os.chown(home,uid,gid)
    env={"PATH":"/usr/bin:/bin","HOME":str(home),"USER":name,"XDG_RUNTIME_DIR":runtime,
         "DBUS_SESSION_BUS_ADDRESS":"unix:path="+runtime+"/bus","LANG":"C.UTF-8"}

    def as_user(*args):
        return cmd("setpriv","--reuid",str(uid),"--regid",str(gid),"--clear-groups","--",*args,env=env)
    try:
        cmd("systemctl","start",manager)
        as_user("systemctl","--user","show-environment")
        yield home,uid,gid,env,as_user
    finally:
        # The randomly named account and both units belong only to this fixture.
        cmd("systemctl","stop",manager)
        cmd("systemctl","stop",f"user-runtime-dir@{uid}.service")
        cmd("userdel",name)
        mail=Path("/var/mail")/name
        if mail.exists():
            info=mail.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_size!=0 or info.st_uid!=uid:
                raise RuntimeError("临时账户邮箱状态与初始创建结果不一致")
            mail.unlink()
        try:
            pwd.getpwnam(name)
        except KeyError:
            pass
        else:
            raise RuntimeError("临时发布用户清理失败")
        if Path(runtime).exists():
            raise RuntimeError("临时用户管理器运行目录仍存在")


def services(stack,run_id,password):
    def container(kind,ports,values):
        name=run_id+"-"+kind
        args=["docker","run","--rm","-d","--pull=never","--name",name]
        for p in ports:
            args.extend(["-p","127.0.0.1::"+str(p)])
        for key in values:
            args.extend(["-e",key])
        cid=cmd(*args,IMAGES[kind],env={**os.environ,**values})
        stack.callback(cmd,"docker","rm","-f","-v",cid)
        return name,[int(cmd("docker","port",cid,str(p)+"/tcp").splitlines()[0].rsplit(":",1)[1]) for p in ports]
    pg,(pg_port,)=container("pg",[5432],{"POSTGRES_USER":"orders","POSTGRES_DB":"orders","POSTGRES_PASSWORD":password})
    redis,(redis_port,)=container("redis",[6379],{})
    broker,(mq_port,management)=container("rabbit",[5672,15672],{
        "RABBITMQ_DEFAULT_USER":"orders","RABBITMQ_DEFAULT_PASS":password,"RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS":"+S 1:1"})
    def sql(query,database="orders"):
        return cmd("docker","exec",pg,"psql","-XAqt","-v","ON_ERROR_STOP=1","-U","orders","-d",database,"-c",query)
    wait(lambda:sql("SELECT 1")=="1")
    sql("CREATE DATABASE orders_candidate")
    headers={"Authorization":"Basic "+base64.b64encode(("orders:"+password).encode()).decode()}
    wait(lambda:fetch(f"http://127.0.0.1:{management}/api/overview",headers=headers))
    cmd("docker","exec",broker,"rabbitmqctl","add_vhost","candidate")
    cmd("docker","exec",broker,"rabbitmqctl","set_permissions","-p","candidate","orders",".*",".*",".*")
    return pg_port,redis_port,mq_port,redis,sql


class Registry:
    """固定任务和内容服务仅用于验证正式发布执行器的 HTTPS、签名及执行路径。"""
    def __init__(self,root,stack,token):
        self.task=None
        self.contents=None
        self.receipts=[]
        registry=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_):
                pass
            def route(self):
                return urlsplit(self.path).path
            def answer(self,obj):
                encoded=json.dumps({"code":0,"data":obj}).encode()
                self.send_response(200);self.send_header("Content-Type","application/json")
                self.send_header("Content-Length",str(len(encoded)));self.end_headers();self.wfile.write(encoded)
            def do_GET(self):
                if self.headers.get("X-Agent-Token")!=token:
                    self.send_error(401);return
                if self.route()=="/api/release-agent/next":
                    self.answer(registry.task);return
                if registry.task and self.route()==f"/api/release-agent/{registry.task['id']}/content":
                    self.send_response(200);self.send_header("Content-Length",str(registry.contents.stat().st_size))
                    self.end_headers()
                    with registry.contents.open("rb") as src:
                        shutil.copyfileobj(src,self.wfile)
                    return
                self.send_error(404)
            def do_POST(self):
                if (self.headers.get("X-Agent-Token")!=token or not registry.task or
                        self.route()!=f"/api/release-agent/{registry.task['id']}/receipt"):
                    self.send_error(403);return
                receipt=json.loads(self.rfile.read(int(self.headers.get("Content-Length","0"))))
                registry.receipts.append(receipt)
                self.answer(None)
        self.cert=root/"tls.pem"
        tlskey=root/"tls.key"
        cmd("openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=cloide-test",
            "-addext","subjectAltName=IP:127.0.0.1","-keyout",str(tlskey),"-out",str(self.cert))
        self.cert.chmod(0o444)
        srv=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(self.cert,tlskey)
        srv.socket=tls.wrap_socket(srv.socket,server_side=True)
        self.origin="https://127.0.0.1:"+str(srv.server_port)
        thread=threading.Thread(target=srv.serve_forever,daemon=True)
        thread.start()
        def close():
            srv.shutdown();thread.join(3);srv.server_close()
        stack.callback(close)


def variant(source,target,entries):
    shutil.copyfile(source,target)
    with zipfile.ZipFile(target,"a",zipfile.ZIP_DEFLATED) as archive:
        for name,contents in entries.items():
            if "BOOT-INF/classes/"+name in archive.namelist():
                raise ValueError("参考 JAR 已含测试专用条目")
            archive.writestr("BOOT-INF/classes/"+name,contents)
    return target


def verify(jar,executor):
    if os.geteuid()!=0:
        raise SystemExit("验收需要有权创建临时用户、用户管理器及独立容器的测试管理员")
    with ExitStack() as stack:
        root=Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="cloide-order-java-")))
        root.chmod(0o711)
        run_id="ordrel-"+secrets.token_hex(6)
        password=secrets.token_hex(20)
        pg,cache,mq,redis,sql=services(stack,run_id,password)
        home,uid,gid,env,as_user=stack.enter_context(account(root))
        units=home/".config/systemd/user"
        units.mkdir(parents=True)
        for directory in (units,units.parent,units.parent.parent):
            directory.chmod(0o755)
        # User manager follows its own home. The service definitions and trust keys are provisioned separately.
        state=home/"releases"
        state.mkdir();os.chown(state,uid,gid)
        runner=root/"node.py"
        shutil.copyfile(executor,runner);runner.chmod(0o444)
        sign=root/"signatures";sign.mkdir(mode=0o755)
        private=root/"signing.key"; public=root/"verification.pem"
        cmd("openssl","genpkey","-algorithm","RSA","-pkeyopt","rsa_keygen_bits:2048","-out",str(private))
        cmd("openssl","pkey","-in",str(private),"-pubout","-out",str(public))
        public.chmod(0o444)
        token=secrets.token_urlsafe(32);tokenfile=home/"node-token"
        tokenfile.write_text(token);tokenfile.chmod(0o400);os.chown(tokenfile,uid,gid)
        registry=Registry(root,stack,token)
        env["SSL_CERT_FILE"]=str(registry.cert)
        tcp_wait=root/"wait-port.py"
        tcp_wait.write_text("import socket,sys,time\n"
            "for _ in range(175):\n"
            "    try:\n"
            "        with socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=.3): break\n"
            "    except OSError: time.sleep(.2)\n"
            "else: raise SystemExit(1)\n")
        tcp_wait.chmod(0o444)
        candidate,active=port(),port()
        while active==candidate:
            active=port()
        config={"control":registry.origin,"server_id":1,"project_id":1,"environment":"test","service":"orders-api",
                "kind":"java-jar","state":str(state),"signatures":str(sign),"public_key":str(public),"token_file":str(tokenfile),
                "probe_contains":"[","active_probe":f"http://127.0.0.1:{active}/api/orders",
                "candidate_probe":f"http://127.0.0.1:{candidate}/api/orders"}
        for slot,num,dbname,vhost,redisdb in [("active",active,"orders","/",0),("candidate",candidate,"orders_candidate","candidate",1)]:
            name=f"{run_id}-{slot}.service"
            config[slot+"_unit"]=name
            environ=home/(slot+".env")
            fields={"ORDERS_DB_URL":f"jdbc:postgresql://127.0.0.1:{pg}/{dbname}","ORDERS_DB_USER":"orders",
                    "ORDERS_DB_PASSWORD":password,"ORDERS_REDIS_HOST":"127.0.0.1","ORDERS_REDIS_PORT":cache,
                    "SPRING_DATA_REDIS_DATABASE":redisdb,"ORDERS_MQ_HOST":"127.0.0.1","ORDERS_MQ_PORT":mq,
                    "ORDERS_MQ_USER":"orders","ORDERS_MQ_PASSWORD":password,"ORDERS_MQ_VHOST":vhost,
                    "ORDERS_PORT":num,"SPRING_PROFILES_ACTIVE":slot}
            environ.write_text("".join(f"{k}={v}\n" for k,v in fields.items()))
            environ.chmod(0o400);os.chown(environ,uid,gid)
            (units/name).write_text("[Unit]\nDescription=Temporary order release acceptance\n[Service]\nType=exec\n"
                f"WorkingDirectory={home}\nEnvironmentFile={environ}\n"
                f"ExecStart=/usr/bin/java -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2 -Xms48m -Xmx192m -jar {state}/{slot}/app.jar\n"
                f"ExecStartPost=/usr/bin/python3 {tcp_wait} {num}\n"
                "Restart=no\nSuccessExitStatus=143\nTimeoutStopSec=12\nKillMode=control-group\nNoNewPrivileges=true\n")
            (units/name).chmod(0o444)
        as_user("systemctl","--user","daemon-reload")
        cfg=home/"release.json";cfg.write_text(json.dumps(config));cfg.chmod(0o444)
        def stop_units():
            as_user("systemctl","--user","stop",config["active_unit"],config["candidate_unit"])
        stack.callback(stop_units)
        v1=jar
        mig={"db/migration/V2__optional_fulfilment_note.sql":"ALTER TABLE orders ADD COLUMN fulfilment_note VARCHAR(80);"}
        v2=variant(jar,root/"v2.jar",mig)
        bad=variant(jar,root/"bad.jar",{**mig,"application-active.properties":"server.servlet.context-path=/misrouted\n"})
        sequence=[]
        def deploy(artifact,expected,active_jar,action="deploy"):
            digest=hashlib.sha256(artifact.read_bytes()).hexdigest()
            sig=sign/(digest+".sig")
            cmd("openssl","dgst","-sha256","-sign",str(private),"-out",str(sig),str(artifact));sig.chmod(0o444)
            task={"id":str(uuid.uuid4()),"action":action,"generation":len(sequence)+1,"kind":"java-jar","sha256":digest,
                  **{k:config[k] for k in ("environment","service")},"projectId":1,"serverId":1}
            registry.task=task;registry.contents=artifact
            as_user("python3",str(runner),str(cfg))
            record=json.loads((state/"release.json").read_text())
            receipt=registry.receipts[-1]
            if not (record["acked"] and record["id"]==task["id"] and record["receipt"]==receipt and receipt["status"]==expected):
                logs=cmd("journalctl","--no-pager","-o","cat","-n","100","_UID="+str(uid))
                hints=[s.replace(password,"[redacted]")[:360] for s in logs.splitlines()
                       if any(w in s for w in ("Exception","Permission denied","status=","java:","Unable to access","unrecognized","Unrecognized"))]
                print("\n".join(hints[-8:]),flush=True)
                raise RuntimeError(f"第 {task['generation']} 次发布终态 {receipt['status']}，证据 {receipt['evidence']}")
            if expected=="failed" and not receipt["evidence"].startswith("候选版本切换失败，已恢复发布前的目标"):
                raise RuntimeError("故障制品未走完候选启动、活动探针失败和恢复")
            assert hashlib.sha256((state/"active/app.jar").read_bytes()).digest()==hashlib.sha256(active_jar.read_bytes()).digest()
            assert as_user("systemctl","--user","show",config["active_unit"],"-p","ActiveState","--value")=="active"
            candidate_state=as_user("systemctl","--user","show",config["candidate_unit"],"-p","ActiveState","--value")
            if candidate_state!="inactive":
                raise RuntimeError("候选单元停止后的状态为 "+candidate_state)
            sequence.append(expected)
            print(f"第 {task['generation']} 次签名发布 {expected}，制品 {digest}，{receipt['evidence']}",flush=True)
        api=f"http://127.0.0.1:{active}/api/orders"
        deploy(v1,"healthy",v1)
        before=fetch(api,{"item":"发布前创建"})
        deploy(v2,"healthy",v2)
        after=fetch(api,{"item":"发布后创建"})
        shipped=fetch(api+"/"+after["id"]+"/ship",method="POST")
        assert shipped["status"]=="shipped"
        deploy(bad,"failed",v2)
        assert fetch(api+"/"+before["id"])==before and fetch(api+"/"+after["id"])==shipped
        deploy(v1,"healthy",v1,action="rollback")
        assert fetch(api+"/"+before["id"])==before and fetch(api+"/"+after["id"])==shipped
        third=fetch(api,{"item":"回滚后创建"})
        assert fetch(api+"/"+third["id"])==third
        wait(lambda:sql("SELECT COUNT(*) FROM order_event_receipts")=="4")
        assert sql("SELECT COUNT(*) FROM order_outbox WHERE sent")=="4"
        assert sql("SELECT COUNT(*) FROM orders")=="3"
        assert sql("SELECT COUNT(*) FROM orders","orders_candidate")=="0"
        assert sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='orders' AND column_name='fulfilment_note'")=="1"
        assert cmd("docker","exec",redis,"redis-cli","EXISTS","orders:"+after["id"]+":2")=="1"
        print(json.dumps({"systemd_user_units":2,"signed_releases":sequence,"preserved_orders":2,
              "created_after_rollback":1,"unique_events":4,"candidate_orders":0,"expanded_column_retained":True}),flush=True)
    print("已停止用户服务和管理器、移除临时账户、三个容器及其卷，清理签名材料和工作目录。",flush=True)


if __name__=="__main__":
    def terminate(signum,_frame):
        raise SystemExit(128+signum)
    for sig in (signal.SIGTERM,signal.SIGHUP,signal.SIGINT):
        signal.signal(sig,terminate)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("jar",type=Path);p.add_argument("executor",type=Path)
    args=p.parse_args()
    verify(args.jar.resolve(strict=True),args.executor.resolve(strict=True))
