"""Restore a new temporary MySQL 8.4 instance with real migration SQL and synthetic credentials."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import uuid
import zipfile

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from control_backup import Mysql, backup, object_path, restore, save


@contextmanager
def isolated_mysql(root, binaries):
    data = root/"mysql"
    data.mkdir()
    password = os.urandom(32).hex()
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1",0))
        port = reservation.getsockname()[1]
    config = root/"mysql.cnf"
    save(config, f"[client]\nhost=127.0.0.1\nport={port}\nprotocol=tcp\nuser=root\npassword={password}\n".encode())
    init = root/"init.sql"
    save(init, f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{password}';\n".encode())
    mysqld = str(binaries/"mysqld") if binaries else "mysqld"
    base = [mysqld, "--no-defaults", "--datadir="+str(data), "--log-error="+str(root/"mysql-error.log")]
    subprocess.run([*base,"--initialize-insecure"],check=True,timeout=120)
    proc = subprocess.Popen([*base,"--bind-address=127.0.0.1",f"--port={port}","--mysqlx=OFF","--skip-log-bin",
                             "--innodb-buffer-pool-size=64M","--init-file="+str(init)],stdout=subprocess.DEVNULL)
    mysql = Mysql(config,binaries)
    owned = False
    try:
        deadline = time.monotonic()+50
        while proc.poll() is None and time.monotonic() < deadline:
            try:
                result = mysql.command("mysql","-NBe","SELECT @@datadir",capture_output=True,timeout=5)
                owned = Path(result.stdout.decode().strip()).resolve() == data.resolve()
                if owned: break
            except (subprocess.SubprocessError,OSError):
                time.sleep(.25)
        if not owned: raise RuntimeError("本次新建的 MySQL 数据目录未能就绪")
        yield mysql
    finally:
        try:
            if owned and proc.poll() is None:
                mysql.command("mysqladmin","shutdown",timeout=25)
                proc.wait(timeout=15)
        finally:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=10)


def migration_sql(server_jar=None):
    server = Path(__file__).resolve().parents[2]/"server"
    directory = server/"src/main/resources/db/migration"
    pattern = re.compile(r"V([0-9]+)__[^/]+\.sql")
    if server_jar is None and directory.is_dir():
        files = {p.name:p.read_bytes() for p in directory.iterdir() if pattern.fullmatch(p.name)}
    else:
        prefix = "BOOT-INF/classes/db/migration/"
        with zipfile.ZipFile(server_jar or server/"target/cloide-server-0.0.1-SNAPSHOT.jar") as jar:
            files = {name[len(prefix):]:jar.read(name) for name in jar.namelist()
                     if name.startswith(prefix) and pattern.fullmatch(name[len(prefix):])}
    if not files:
        raise ValueError("未找到 Flyway SQL 迁移，无法验收恢复")
    return [files[name] for name in sorted(files,key=lambda n:int(pattern.fullmatch(n).group(1)))]


def run(binaries, scratch, server_jar=None):
    files = migration_sql(server_jar)
    with tempfile.TemporaryDirectory(prefix="cloide-recovery-drill-", dir=scratch) as temp:
        root = Path(temp).resolve()
        with isolated_mysql(root,binaries) as mysql:
            db = "cloide_test_source"
            mysql.query("CREATE DATABASE "+db+" CHARACTER SET utf8mb4")
            for sql in files:
                mysql.command("mysql","--default-character-set=utf8mb4","--database="+db,input=sql,stdout=subprocess.DEVNULL)
            aes = os.urandom(32)
            nonce, token = os.urandom(12), os.urandom(32).hex()
            cipher = "v1:"+base64.b64encode(nonce+AESGCM(aes).encrypt(nonce,b"synthetic",b"cloide:server:17:credential:ssh_password")).decode()
            sha = hashlib.sha256(token.encode()).hexdigest()
            mysql.query(f"""INSERT INTO servers(id,name,host,ssh_host,ssh_user,ssh_auth_type,ssh_password_encrypted,agent_token_hash)
                VALUES(17,'build','example.test','example.test','test','password','{cipher}','sha256:{sha}');
                INSERT INTO projects(id,name) VALUES(22,'orders');
                INSERT INTO project_nodes VALUES(17,22,'dev');""",db)
            art = root/"original-artifacts"
            payload = b"immutable release artifact\n" * 50000
            sha = hashlib.sha256(payload).hexdigest()
            path = object_path(art,sha)
            path.parent.mkdir(parents=True)
            save(path,payload)
            mysql.query(f"""INSERT INTO project_artifacts(id,project_id,run_id,repository,commit_sha,kind,sha256,byte_count,uploaded_by,created_at)
                              VALUES('{uuid.uuid4()}',22,'{uuid.uuid4()}','orders','{'a'*40}',
                              'java_jar','{sha}',{len(payload)},1,UTC_TIMESTAMP(6));""",db)
            env = root/"source.env"
            keyfile = root/"sample-app.pem"
            save(keyfile,os.urandom(128))
            save(env,("AES_GCM_KEY="+base64.b64encode(aes).decode()+"\nJWT_SECRET="+os.urandom(32).hex()
                      +"\nCLOIDE_GITHUB_PRIVATE_KEY_FILE="+str(keyfile)
                      +"\nDB_HOST=source.example.test\nDB_NAME=cloide_test_source\n").encode())
            samples = root/"token-sample.json"
            save(samples,json.dumps({"17":token}).encode())
            archive, key = root/"backup", os.urandom(32)
            started = time.monotonic()
            backup(mysql,db,env,art,archive,key,root)
            before = mysql.query("SHOW DATABASES")
            blob = archive/"objects"/(sha+".enc")
            data = blob.read_bytes()
            blob.write_bytes(data[:-1]+bytes([data[-1]^1]))
            try:
                restore(mysql,archive,root/"corrupt-validation",key,samples)
                raise AssertionError("损坏的制品被接受")
            except InvalidTag:
                assert mysql.query("SHOW DATABASES") == before
            blob.write_bytes(data)
            result = restore(mysql,archive,root/"recovered",key,samples)
            assert result["nodeTokenSampleChecked"] and result["decryptedEnvelopes"] == 1
            assert object_path(root/"recovered"/"artifacts",sha).read_bytes() == payload
            assert (root/"recovered"/"source-env.material").read_bytes() == env.read_bytes()
            assert (root/"recovered"/"key-material"/"github-app").read_bytes() == keyfile.read_bytes()
            assert b"source.example.test" not in (root/"recovered"/"recovery.env").read_bytes()
            assert b"ENGINEERING_RECOVERY_MODE=true" in (root/"recovered"/"recovery.env").read_bytes()
            assert mysql.query("SELECT COUNT(*) FROM servers", db) == [["1"]]
            result.update({"mysql":mysql.query("SELECT VERSION()")[0][0],"appliedMigrationSql":len(files),
                           "artifactBytes":len(payload),"corruptedObjectRejectedBeforeDatabaseCreate":True,
                           "seconds":round(time.monotonic()-started,2)})
    result["temporaryMysqlAndFilesRemoved"] = True
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-bin",type=Path)
    parser.add_argument("--server-jar",type=Path,help="use Flyway SQL from this Spring Boot release JAR")
    parser.add_argument("--scratch",type=Path,help="existing directory for the isolated temporary instance")
    args = parser.parse_args()
    run(args.mysql_bin,args.scratch,args.server_jar)
