"""Authenticated control-plane backup and side-by-side MySQL recovery. Requires Python 3.11+ and cryptography."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CHUNK = 1024 * 1024
HASH = re.compile("[a-f0-9]{64}")
DATABASE = re.compile("[a-zA-Z0-9_]{1,64}")
HEADER = b"CLOIDE-RECOVERY-1\x00"


def private_file(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("输入必须是普通文件")
    return path


def read_key(path):
    key = private_file(path).read_bytes()
    if len(key) != 32: raise ValueError("独立备份密钥必须为 32 个随机二进制字节")
    return key


def new_file(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600), "wb")


def save(path, data):
    with new_file(path) as file:
        file.write(data)


def fields(raw):
    values = {}
    for line in raw.decode("utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            name, value = line.strip().split("=", 1)
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            values[name] = value
    return values


def assert_keys(raw):
    values = fields(raw)
    if len(base64.b64decode(values.get("AES_GCM_KEY", ""), validate=True)) != 32 or len(values.get("JWT_SECRET", "")) < 32:
        raise ValueError("环境材料中缺少有效 AES_GCM_KEY 或 JWT_SECRET")
    return values


def crypt(source, output, key, aad, decrypt=False):
    """nonce | ciphertext | GCM tag; plaintext is only released from private staging after full authentication."""
    digest = hashlib.sha256()
    size = 0
    with private_file(source).open("rb") as reader, new_file(output) as writer:
        if decrypt:
            total = reader.seek(0, os.SEEK_END)
            if total < 28: raise ValueError("密文截断")
            reader.seek(total-16)
            tag = reader.read(16)
            reader.seek(0)
            nonce = reader.read(12)
            transform = Cipher(algorithms.AES(key), modes.GCM(nonce, tag)).decryptor()
            remaining = total-28
        else:
            nonce = os.urandom(12)
            writer.write(nonce)
            transform = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
            remaining = source.stat().st_size
        transform.authenticate_additional_data(aad)
        while remaining:
            block = reader.read(min(CHUNK, remaining))
            if not block: raise ValueError("输入在复制期间被截断")
            converted = transform.update(block)
            writer.write(converted)
            plain = converted if decrypt else block
            digest.update(plain)
            size += len(plain)
            remaining -= len(block)
        if not decrypt and reader.read(1): raise ValueError("输入在复制期间改变")
        writer.write(transform.finalize())
        if not decrypt: writer.write(transform.tag)
        writer.flush()
        os.fsync(writer.fileno())
    return {"sha256":digest.hexdigest(), "bytes":size}


def object_path(root, sha):
    if not HASH.fullmatch(sha): raise ValueError("无效的制品 SHA-256")
    path = root / sha[:2] / sha / "blob"
    if root.resolve() != root or path.resolve() != path:
        raise ValueError("制品目录中存在非预期的符号链接")
    return path


class Mysql:
    def __init__(self, options, binaries):
        self.options = str(private_file(options).resolve())
        self.binaries = binaries

    def command(self, tool, *args, **options):
        executable = str(self.binaries / tool) if self.binaries else tool
        return subprocess.run([executable, "--defaults-file="+self.options, "--no-login-paths", *args],
                              check=True, **options)

    def query(self, sql, database=None):
        args = ["--batch", "--skip-column-names", "--raw", "--default-character-set=utf8mb4"]
        if database:
            if not DATABASE.fullmatch(database): raise ValueError("数据库名非法")
            args.append("--database="+database)
        data = self.command("mysql", *args, input=sql.encode(), stdout=subprocess.PIPE).stdout.decode("utf-8").splitlines()
        return [line.split("\t") for line in data]

    def snapshot(self, db):
        columns = "id,sha256,byte_count,project_id,run_id,repository,commit_sha,kind,uploaded_by"
        if self.query("SHOW COLUMNS FROM project_artifacts LIKE 'source_node_id'",db):
            columns += ",source_node_id"
        rows = self.query("SELECT "+columns+" FROM project_artifacts ORDER BY id", db)
        blobs = {}
        for row in rows:
            sha, size = row[1:3]
            if not HASH.fullmatch(sha) or int(size) < 1 or blobs.setdefault(sha, int(size)) != int(size):
                raise ValueError("制品数据库索引不一致")
        nodes = self.query("""SELECT id,SHA2(CONCAT_WS('|',COALESCE(agent_token_hash,''),COALESCE(agent_token_hash_prev,''),
          COALESCE(agent_token_grace_until,''),COALESCE(agent_token_revoked_at,''),deleted),256) FROM servers ORDER BY id""", db)
        assignments = self.query("SELECT server_id,project_id,environment FROM project_nodes ORDER BY server_id", db)
        def fingerprint(values):
            return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()
        return {"blobs":blobs, "artifactRows":len(rows), "artifactIndex":fingerprint(rows), "nodes":len(nodes),
                "nodeCredentials":fingerprint(nodes), "assignments":fingerprint(assignments)}

    def verify_envelopes(self, db, env):
        aes = AESGCM(base64.b64decode(assert_keys(env)["AES_GCM_KEY"]))
        queries = [
            ("SELECT id,ssh_password_encrypted FROM servers WHERE ssh_password_encrypted IS NOT NULL","cloide:server:{}:credential:ssh_password"),
            ("SELECT id,ssh_private_key_encrypted FROM servers WHERE ssh_private_key_encrypted IS NOT NULL","cloide:server:{}:credential:ssh_private_key"),
            ("SELECT id,ssh_private_key_passphrase_encrypted FROM servers WHERE ssh_private_key_passphrase_encrypted IS NOT NULL","cloide:server:{}:credential:ssh_private_key_passphrase"),
            ("SELECT user_id,api_key_ciphertext FROM ai_user_provider_configs","cloide:user:{}:credential:ai_api_key"),
            ("SELECT 0,bootstrap_token_cipher FROM auth_bootstrap_state WHERE bootstrap_token_cipher IS NOT NULL","cloide:bootstrap:token:v1"),
        ]
        count = 0
        for sql, aad in queries:
            for owner, value in self.query(sql, db):
                if not value.startswith("v1:"): raise ValueError("数据库包含无法识别的凭据信封")
                raw = base64.b64decode(value[3:], validate=True)
                aes.decrypt(raw[:12], raw[12:], aad.format(owner).encode())
                count += 1
        return count


def aad(uid, name):
    return HEADER + uid.encode() + b"/" + name.encode()


def backup(mysql, db, env_file, artifact_root, dest, key, scratch, materials=None):
    if not DATABASE.fullmatch(db): raise ValueError("数据库名非法")
    env = private_file(env_file).read_bytes()
    values = assert_keys(env)
    materials = dict(materials or {})
    if values.get("CLOIDE_GITHUB_PRIVATE_KEY_FILE"):
        materials.setdefault("github-app",Path(values["CLOIDE_GITHUB_PRIVATE_KEY_FILE"]))
    if any(not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}",name) for name in materials):
        raise ValueError("附加密钥材料的名称应由小写字母、数字、下划线和连字符构成")
    # A single-transaction dump is consistent for the platform's InnoDB base tables.
    unsupported = mysql.query("""SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND (table_type <> 'BASE TABLE' OR engine <> 'InnoDB') UNION ALL
        SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() UNION ALL
        SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE() UNION ALL
        SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE()""", db)
    if any(row != ["0"] for row in unsupported): raise ValueError("发现平台模板外的数据库对象，请先纳入恢复策略")
    before = mysql.snapshot(db)
    mysql.verify_envelopes(db, env)
    uid = str(uuid.uuid4())
    dest.mkdir(mode=0o700, exist_ok=False)
    (dest/"objects").mkdir(mode=0o700)
    (dest/"key-material").mkdir(mode=0o700)
    with tempfile.TemporaryDirectory(dir=scratch) as tmp:
        stage = Path(tmp)
        with new_file(stage/"dump.sql") as file:
            mysql.command("mysqldump", "--single-transaction", "--skip-add-drop-table", "--skip-lock-tables",
                          "--quick", "--hex-blob", "--skip-triggers", "--no-tablespaces", "--set-gtid-purged=OFF",
                          "--default-character-set=utf8mb4", db, stdout=file)
        if mysql.snapshot(db) != before or env_file.read_bytes() != env:
            raise ValueError("快照期间节点授权、制品索引或环境密钥发生变化，请重试")
        metadata = {"schema":1, "id":uid, "sourceDatabase":db, "createdAt":datetime.now(timezone.utc).isoformat(),
                    "index":before, "files":{}, "materials":{}}
        save(stage/"server.env", env)
        for name in ("dump.sql", "server.env"):
            metadata["files"][name] = crypt(stage/name, dest/(name+".enc"), key, aad(uid,name))
        for name, path in materials.items():
            metadata["materials"][name] = crypt(path,dest/"key-material"/(name+".enc"),key,aad(uid,"key-material/"+name))
        for sha, size in before["blobs"].items():
            info = crypt(object_path(artifact_root,sha), dest/"objects"/(sha+".enc"), key, aad(uid,sha))
            if info != {"sha256":sha, "bytes":size}: raise ValueError("受管制品内容与数据库不一致")
        nonce = os.urandom(12)
        encrypted = AESGCM(key).encrypt(nonce,json.dumps(metadata).encode(),aad(uid,"manifest"))
        # Written last. Incomplete directories have no authenticated manifest.
        save(dest/"manifest.enc", uuid.UUID(uid).bytes + nonce + encrypted)
    return {"snapshot":uid, "artifactRows":before["artifactRows"], "objects":len(before["blobs"]), "nodes":before["nodes"]}


def restore(mysql, archive, target, key, token_file):
    manifest = private_file(archive/"manifest.enc").read_bytes()
    uid = str(uuid.UUID(bytes=manifest[:16]))
    meta = json.loads(AESGCM(key).decrypt(manifest[16:28], manifest[28:], aad(uid,"manifest")))
    if meta["schema"] != 1 or meta["id"] != uid: raise ValueError("快照格式不匹配")
    target.mkdir(mode=0o700, exist_ok=False)
    with tempfile.TemporaryDirectory(dir=target) as tmp:
        stage = Path(tmp)
        (stage/"artifacts").mkdir(mode=0o700)
        (stage/"key-material").mkdir(mode=0o700)
        for name in ("dump.sql", "server.env"):
            if crypt(archive/(name+".enc"), stage/name, key, aad(uid,name), True) != meta["files"][name]:
                raise ValueError("恢复内容校验失败")
        for sha, size in meta["index"]["blobs"].items():
            path = object_path(stage/"artifacts",sha)
            path.parent.mkdir(mode=0o700, parents=True)
            if crypt(archive/"objects"/(sha+".enc"), path, key, aad(uid,sha), True) != {"sha256":sha, "bytes":size}:
                raise ValueError("恢复制品校验失败")
        for name, info in meta.get("materials",{}).items():
            if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}",name): raise ValueError("密钥材料名称非法")
            if crypt(archive/"key-material"/(name+".enc"),stage/"key-material"/name,key,aad(uid,"key-material/"+name),True) != info:
                raise ValueError("附加密钥材料校验失败")
        db = "cloide_restore_" + uuid.uuid4().hex
        # The trusted dump targets the current DB only. A collision fails CREATE before SQL is imported.
        mysql.query(f"CREATE DATABASE `{db}` CHARACTER SET utf8mb4")
        save(target/"database-name", (db+"\n").encode())
        with (stage/"dump.sql").open("rb") as file:
            mysql.command("mysql", "--binary-mode", "--database="+db, stdin=file)
        if mysql.snapshot(db) != meta["index"]: raise ValueError("恢复后的节点、分配或制品索引对账失败")
        raw = (stage/"server.env").read_bytes()
        verified = mysql.verify_envelopes(db,raw)
        if token_file:
            for server_id, token in json.loads(private_file(token_file).read_text(encoding="utf-8")).items():
                if not re.fullmatch("[1-9][0-9]{0,18}",server_id): raise ValueError("节点 ID 非法")
                rows = mysql.query(f"SELECT agent_token_hash FROM servers WHERE id={server_id} AND deleted=0 AND agent_token_revoked_at IS NULL",db)
                expected = "sha256:" + hashlib.sha256(token.encode()).hexdigest()
                if len(rows) != 1 or not hmac.compare_digest(rows[0][0],expected): raise ValueError("抽样节点凭据未通过")
        (stage/"artifacts").rename(target/"artifacts")
        (stage/"key-material").rename(target/"key-material")
        save(target/"source-env.material", raw)
        values = fields(raw)
        # Copy cryptographic values only. Source DB, queues, outbound providers and public ingress are never auto-connected.
        isolated = ["SERVER_ADDRESS=127.0.0.1", "PROJECT_ISOLATION_ENABLED=true", "ENGINEERING_RECOVERY_MODE=true",
                    "DB_NAME="+db, "DB_HOST=", "DB_PORT=", "DB_USER=", "DB_PASSWORD=",
                    "CLOIDE_ARTIFACTS_DIRECTORY="+str(target/"artifacts")]
        isolated.extend(k+"="+values[k] for k in ("AES_GCM_KEY","JWT_SECRET","AGENT_REGISTER_KEY") if values.get(k))
        save(target/"recovery.env", ("\n".join(isolated)+"\n").encode())
        result = {"snapshot":uid, "database":db, "state":"reconciled_offline", "nodes":meta["index"]["nodes"],
                  "artifactRows":meta["index"]["artifactRows"], "objects":len(meta["index"]["blobs"]),
                  "decryptedEnvelopes":verified, "keyMaterialFiles":len(meta.get("materials",{})),
                  "nodeTokenSampleChecked":bool(token_file)}
        save(target/"reconciliation.json",json.dumps(result,indent=2).encode())
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation",required=True)
    keygen = commands.add_parser("keygen")
    keygen.add_argument("file",type=Path)
    for name in ["backup","restore-new"]:
        sub = commands.add_parser(name)
        sub.add_argument("--mysql-options",type=Path,required=True,help="private MySQL client option file")
        sub.add_argument("--mysql-bin",type=Path)
        sub.add_argument("--key-file",type=Path,required=True)
        sub.add_argument("--archive",type=Path,required=True)
        if name == "backup":
            sub.add_argument("--database",required=True)
            sub.add_argument("--environment",type=Path,required=True)
            sub.add_argument("--artifacts",type=Path,required=True)
            sub.add_argument("--scratch",type=Path)
            sub.add_argument("--key-material",action="append",default=[],metavar="NAME=PATH")
        else:
            sub.add_argument("--new-directory",type=Path,required=True)
            sub.add_argument("--node-token-samples",type=Path)
    args = parser.parse_args()
    if args.operation == "keygen":
        save(args.file,os.urandom(32))
        return
    mysql, key = Mysql(args.mysql_options,args.mysql_bin),read_key(args.key_file)
    if args.operation == "backup":
        materials = {}
        for spec in args.key_material:
            name, separator, path = spec.partition("=")
            if not separator or name in materials: raise ValueError("请为每份密钥材料提供独立的名称和路径")
            materials[name] = Path(path)
        result = backup(mysql,args.database,args.environment,args.artifacts.resolve(),args.archive.resolve(),key,args.scratch,materials)
    else:
        result = restore(mysql,args.archive.resolve(),args.new_directory.resolve(),key,args.node_token_samples)
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError,OSError,subprocess.SubprocessError,InvalidTag,KeyError) as err:
        detail = str(err) if isinstance(err,(ValueError,OSError)) else type(err).__name__
        if isinstance(err,InvalidTag): detail = "密钥或完整性标签无效。"
        print("控制面备份或恢复失败。"+detail,file=sys.stderr)
        sys.exit(1)
