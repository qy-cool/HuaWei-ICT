"""Continuous WAL archiving and recovery to a named point for the managed PostgreSQL template."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

from instance import ADMIN, APP, VHOST, cli, compose, execute, initialize, now, read_json, up


WAL = "/cloide-wal"
ARCHIVE_COMMAND = """set -eu; dst=/cloide-wal/%f; if test -f "$dst"; then cmp '%p' "$dst"; else
t=$(mktemp /cloide-wal/.incoming.XXXXXXXX); trap 'rm -f "$t"' EXIT; cp '%p' "$t"; sync "$t";
ln "$t" "$dst" || cmp "$t" "$dst"; fi; sync /cloide-wal""".replace("\n"," ")


def save(path, obj):
    with tempfile.NamedTemporaryFile(dir=path.parent, mode="w", encoding="utf8", delete=False) as file:
        try:
            json.dump(obj,file,indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        except BaseException:
            os.unlink(file.name)
            raise
    os.replace(file.name,path)
    fd = os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


def query(root, sql):
    return execute(root,"psql","-XAtq","-v","ON_ERROR_STOP=1","-U",ADMIN,"-d",VHOST,
                   input=sql.encode(),stdout=subprocess.PIPE).stdout.decode().strip()


def mount(path, target, readonly):
    return {"type":"bind","source":str(path),"target":target,"read_only":readonly,
            "bind":{"create_host_path":False}}


def container(root):
    ident = compose(root,"ps","-q","service",capture_output=True,text=True).stdout.strip()
    if not re.fullmatch("[0-9a-f]{64}",ident):
        raise RuntimeError("需要已启动的单实例")
    image = cli("docker","inspect","--format","{{.Image}}",ident,capture_output=True,text=True).stdout.strip()
    return ident, image


def meta(source):
    row = read_json(source/"instance.json")
    if row["service"]!="postgres": raise ValueError("仅支持 PostgreSQL 实例")
    return row


def store(source):
    row = meta(source)
    vault = Path(read_json(source/"pitr-source.json")["archive"]).resolve(strict=True)
    index = read_json(vault/"cluster.json")
    if index["source"]!=row["id"] or query(source,"SELECT system_identifier FROM pg_control_system()")!=index["system"]:
        raise ValueError("实例和 WAL 归档来源不符")
    return vault,index


def enable(source, vault):
    row = meta(source)
    marker = source/"pitr-source.json"
    if marker.exists():
        if Path(read_json(marker)["archive"])!=vault: raise ValueError("实例已绑定其他归档")
    else:
        if vault==source or source in vault.parents or vault in source.parents:
            raise ValueError("归档与源实例应使用独立目录")
        vault.mkdir(mode=0o700)
        (vault/"wal").mkdir(mode=0o700)
        (vault/"bases").mkdir(mode=0o700)
        (vault/"points").mkdir(mode=0o700)
        save(vault/"cluster.json",{"source":row["id"],"image":row["image"],"createdAt":now()})
        save(marker,{"archive":str(vault)})
    index = read_json(vault/"cluster.json")
    if index["source"]!=row["id"]: raise ValueError("归档来源不符")
    spec = read_json(source/"compose.json")
    svc = spec["services"]["service"]
    binding = mount(vault/"wal",WAL,False)
    command = ["postgres","-c","archive_mode=on","-c","archive_timeout=60s",
               "-c","archive_command="+ARCHIVE_COMMAND.replace("$","$$")]
    if svc.get("command") not in (None,command): raise ValueError("需要审查已有 PostgreSQL 自定义启动参数")
    if binding not in svc["volumes"]:
        cli("docker","run","--rm","--network","none","--volume",str(vault/"wal")+":/wal",
            "--entrypoint","sh",row["image"],"-ec","chown postgres:postgres /wal && chmod 0700 /wal")
        svc["volumes"].append(binding)
    svc["command"] = command
    save(source/"compose.json",spec)
    up(source)
    system = query(source,"SELECT system_identifier FROM pg_control_system()")
    if index.get("system",system)!=system: raise ValueError("数据库系统标识已改变，须另建归档")
    _,image = container(source)
    save(vault/"cluster.json",{**index,"system":system,"imageId":image})
    return {"source":row["id"],"system":system,"archiveMode":query(source,"SHOW archive_mode")}


def base(source):
    vault,index = store(source)
    if query(source,"SELECT count(*) FROM pg_tablespace WHERE spcname NOT IN ('pg_default','pg_global')")!="0":
        raise ValueError("当前物理恢复模板需要默认表空间布局")
    ident,image = container(source)
    if image!=index["imageId"]: raise ValueError("镜像已变化，需要重新评估物理备份兼容性")
    name = str(uuid.uuid4())
    slot = vault/"bases"/name
    slot.mkdir(mode=0o700)
    # Share only loopback networking with the source, keeping replication traffic internal.
    cli("docker","run","--rm","--pull=never","--network","container:"+ident,
        "--user",f"{os.geteuid()}:{os.getegid()}","--cap-drop","ALL","--security-opt","no-new-privileges",
        "-v",str(source/"admin")+":/cloide-admin:ro","-v",str(slot)+":/backup",
        "--entrypoint","sh",image,"-ec",
        'export PGPASSWORD="$(cat /cloide-admin)"; pg_basebackup -h 127.0.0.1 -U '+ADMIN+
        ' -D /backup/data -X stream --checkpoint=fast --manifest-checksums=SHA256; pg_verifybackup /backup/data',
        timeout=21600)
    manifest = read_json(slot/"data"/"backup_manifest")
    ranges = manifest["WAL-Ranges"]
    if len(ranges)!=1: raise ValueError("本模板仅接受单一时间线的完整备份")
    row = {"id":name,"system":index["system"],"imageId":image,"timeline":ranges[0]["Timeline"],
           "end":ranges[0]["End-LSN"],"completedAt":now()}
    save(slot/"base.json",row)
    return row


def point(source,label):
    if not re.fullmatch("[a-z][a-z0-9_]{0,29}",label): raise ValueError("恢复点使用 1–30 位小写字母、数字和下划线")
    vault,index = store(source)
    name = label+"_"+uuid.uuid4().hex[:16]
    lsn = query(source,f"SELECT pg_create_restore_point('{name}')")
    segment = query(source,f"SELECT pg_walfile_name('{lsn}'::pg_lsn)")
    if not re.fullmatch("[A-F0-9]{24}",segment): raise RuntimeError("WAL 段名异常")
    query(source,"SELECT pg_switch_wal()")
    deadline = time.monotonic()+120
    while time.monotonic()<deadline:
        check = compose(source,"exec","-T","service","sh","-c",'test ! -s "$1" || sha256sum "$1"',
                        "check",WAL+"/"+segment,capture_output=True,text=True).stdout.strip()
        if check: break
        time.sleep(1)
    else: raise RuntimeError("恢复点的 WAL 尚未成功归档，检查 pg_stat_archiver 和磁盘")
    row = {"name":name,"system":index["system"],"lsn":lsn,"segment":segment,
           "timeline":int(segment[:8],16),"sha256":check.split()[0],"archivedAt":now()}
    save(vault/"points"/(name+".json"),row)
    return row


def lsn_value(value):
    if not re.fullmatch("[A-Fa-f0-9]{1,8}/[A-Fa-f0-9]{1,8}",value): raise ValueError("无效的 LSN")
    high,low = value.split("/")
    return (int(high,16)<<32)+int(low,16)


def recover(target,vault,base_id,point_name,replay_timeout=3600):
    if not 30<=replay_timeout<=86400: raise ValueError("重放超时应在 30 秒至 24 小时之间")
    base_id = str(uuid.UUID(base_id))
    if not re.fullmatch("[a-z][a-z0-9_]{0,29}_[a-f0-9]{16}",point_name): raise ValueError("无效的恢复点名称")
    cluster = read_json(vault/"cluster.json")
    slot = vault/"bases"/base_id
    b = read_json(slot/"base.json")
    p = read_json(vault/"points"/(point_name+".json"))
    if (p["name"]!=point_name or b["id"]!=base_id or not p["system"]==b["system"]==cluster["system"]
            or p["timeline"]!=b["timeline"] or lsn_value(p["lsn"])<=lsn_value(b["end"])
            or not re.fullmatch("[0-9A-F]{24}",p["segment"]) or not re.fullmatch("[a-f0-9]{64}",p["sha256"])):
        raise ValueError("基础备份、时间线和目标恢复点不相容")
    image = cli("docker","image","inspect","--format","{{.Id}}",cluster["image"],capture_output=True,text=True).stdout.strip()
    if not image==b["imageId"]==cluster["imageId"]: raise ValueError("目标主机需要使用源镜像")
    if vault==target or vault in target.parents or target in vault.parents: raise ValueError("恢复目标需要独立目录")
    initialize(target,"postgres",cluster["image"])
    report = {"state":"restoring","base":base_id,"point":point_name,"system":p["system"]}
    save(target/"restore.json",report)
    normal = read_json(target/"compose.json")
    private = read_json(target/"compose.json")
    svc = private["services"]["service"]
    svc["volumes"].extend([mount(slot/"data","/base",True),mount(vault/"wal",WAL,True)])
    svc["restart"] = "no"
    svc["healthcheck"]["start_period"] = str(replay_timeout)+"s"
    svc["command"] = ["postgres","-c","listen_addresses=","-c","archive_mode=off","-c","restore_command=cp /cloide-wal/%f %p",
                      "-c","recovery_target_name="+point_name,"-c","recovery_target_timeline="+str(p["timeline"]),
                      "-c","recovery_target_action=promote"]
    save(target/"compose.json",private)
    try:
        compose(target,"run","--rm","-T","--no-deps","--entrypoint","sh","service","-ec",
                'test ! -e "$PGDATA/PG_VERSION"; pg_verifybackup /base; '
                'printf "%s  %s\\n" "$1" "$2" | sha256sum -c -; '
                'mkdir -p "$PGDATA"; test -z "$(ls -A "$PGDATA")"; cp -a /base/. "$PGDATA/"; '
                'touch "$PGDATA/recovery.signal"; chown -R postgres:postgres "$PGDATA"; chmod 700 "$PGDATA"; sync -f "$PGDATA"',
                "restore",p["sha256"],WAL+"/"+p["segment"],timeout=21600)
        deadline = time.monotonic()+replay_timeout
        compose(target,"up","-d","--wait","--wait-timeout",str(replay_timeout),timeout=replay_timeout+30)
        proof = ""
        while time.monotonic() < deadline:
            proof = query(target,"SELECT system_identifier,pg_is_in_recovery(),pg_last_wal_replay_lsn() FROM pg_control_system()")
            if proof.startswith(cluster["system"]+"|f|"): break
            time.sleep(1)
        else: raise RuntimeError("恢复未完成")
        replay = proof.split("|")[2]
        if lsn_value(replay)!=lsn_value(p["lsn"]): raise ValueError("实际重放位置与命名恢复点不符")
        # Local sockets only until both template credentials are replaced.
        for file,role in (("admin",ADMIN),("app",APP)):
            pw = (target/file).read_text()
            if not re.fullmatch("[a-f0-9]{64}",pw): raise ValueError("目标凭据无效")
            query(target,f"ALTER ROLE {role} PASSWORD '{pw}'")
        compose(target,"stop","service")
        save(target/"compose.json",normal)
        up(target,restoring=True)
        report.update(state="service_checked",replayedLsn=replay,verifiedAt=now())
        save(target/"restore.json",report)
        return report
    except Exception:
        compose(target,"stop","service")
        raise


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command",required=True)
    for name in ("enable","base","point","restore-new"):
        s = commands.add_parser(name)
        s.add_argument("instance",type=Path)
        if name in ("enable","restore-new"): s.add_argument("archive",type=Path)
        if name=="point": s.add_argument("label")
        if name=="restore-new":
            s.add_argument("base_id")
            s.add_argument("point_name")
            s.add_argument("--replay-timeout",type=int,default=3600,help="seconds to wait for recovery")
    args = parser.parse_args()
    root = args.instance.resolve()
    if args.command=="enable": result=enable(root,args.archive.resolve())
    elif args.command=="base": result=base(root)
    elif args.command=="point": result=point(root,args.label)
    else: result=recover(root,args.archive.resolve(),args.base_id,args.point_name,args.replay_timeout)
    print(json.dumps(result))
