"""Exercise backup and restore on new isolated volumes. Never accepts an existing instance."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from instance import APP, ADMIN, IMAGES, backup, check, cli, compose, execute, initialize, read_json, redis, restore_new, topology, up


def sql(root, statement):
    env = {**os.environ, "PGPASSWORD": (root / "app").read_text()}
    return compose(root, "exec", "-T", "-e", "PGPASSWORD", "service", "psql", "-h", "127.0.0.1",
                   "-XAt", "-v", "ON_ERROR_STOP=1", "-U", APP, "-d", "orders",
                   env=env, input=statement.encode(), stdout=subprocess.PIPE).stdout.decode().strip()


def publish(root, event):
    confirmation = topology(root, "exchanges/orders/orders.events/publish", "POST",
                            {"routing_key": "order.changed", "properties": {"message_id": event, "delivery_mode": 2},
                             "payload": json.dumps({"id": event, "state": "cancelled"}), "payload_encoding": "string"})
    assert confirmation["routed"]


def receive(root, mode):
    return topology(root, "queues/orders/orders.consumer/get", "POST",
                    {"count": 10, "ackmode": mode, "encoding": "auto", "truncate": 1000})


def check_messages(root, events):
    for event in events:
        publish(root, event)
    first = receive(root, "ack_requeue_true")
    second = receive(root, "ack_requeue_false")
    assert sorted(row["properties"]["message_id"] for row in first) == sorted(events)
    assert all(row["redelivered"] for row in second)
    # A duplicate publication is expected after business outbox replay.
    applied = {json.loads(row["payload"])["id"] for row in second}
    assert applied == set(events) and len(second) == len(events)
    assert not receive(root, "ack_requeue_false")


def redis_cmd(root, *command):
    if command[0] == "INFO":
        return redis(root, "--raw", *command, stdout=subprocess.PIPE).stdout.decode().strip()
    env = {**os.environ, "REDISCLI_AUTH": (root / "app").read_text()}
    return compose(root, "exec", "-T", "-e", "REDISCLI_AUTH", "service", "redis-cli", "-e", "--raw",
                   "--user", APP, *command, env=env, stdout=subprocess.PIPE).stdout.decode().strip()


def seed(root, kind):
    if kind == "postgres":
        sql(root, """CREATE TABLE orders (id bigint PRIMARY KEY, state text NOT NULL CHECK(state IN ('new','cancelled')));
INSERT INTO orders VALUES (117, 'new'), (118,'cancelled');""")
    elif kind.startswith("redis-"):
        assert redis_cmd(root, "SET", "order:117", "new", "EX", "600") == "OK"
    else:
        check_messages(root, ["order-117", "order-118", "order-117"])
        publish(root, "outbox-119")


def verify(root, kind):
    if kind == "postgres":
        assert sql(root, "SELECT string_agg(id || ':' || state, ',' ORDER BY id) FROM orders") == "117:new,118:cancelled"
        assert sql(root, "SELECT rolsuper FROM pg_roles WHERE rolname=current_user") == "f"
        assert sql(root, "SELECT count(*) FROM pg_constraint WHERE conrelid='orders'::regclass AND contype IN ('c','p')") == "2"
        sql(root, "BEGIN; INSERT INTO orders VALUES (119,'new'); UPDATE orders SET state='cancelled' WHERE id=119; ROLLBACK;")
    elif kind == "redis-persistent":
        assert redis_cmd(root, "GET", "order:117") == "new"
        assert 0 < int(redis_cmd(root, "TTL", "order:117")) <= 600
        assert "aof_enabled:1" in redis_cmd(root, "INFO", "persistence")
    elif kind == "redis-cache":
        assert redis_cmd(root, "DBSIZE") == "0"
        # Model a cache miss followed by an application read of its source of truth.
        assert redis_cmd(root, "SET", "order:117", "new", "EX", "600") == "OK"
        assert redis_cmd(root, "GET", "order:117") == "new"
    else:
        assert not receive(root, "ack_requeue_false")
        check_messages(root, ["outbox-119", "outbox-119"])


def cleanup(root):
    if not (root / "compose.json").exists():
        return
    project = "cloide-mw-" + read_json(root / "instance.json")["id"].replace("-", "")
    compose(root, "down", "--volumes", "--timeout", "10")
    for resource in [("container", "ls", "-aq"), ("volume", "ls", "-q"), ("network", "ls", "-q")]:
        leftover = cli("docker", *resource, "--filter", "label=com.docker.compose.project="+project, capture_output=True).stdout.strip()
        if leftover:
            raise RuntimeError(f"演练资源尚未清理：{project} {resource[0]}")


def drill(kind, image):
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="cloide-middleware-drill-") as workspace:
        source, target, archive = (Path(workspace) / name for name in ("source", "target", "backup"))
        try:
            initialize(source, kind, image)
            up(source)
            check(source)
            seed(source, kind)
            backup(source, archive)
            meta = read_json(archive / "manifest.json")
            compose(source, "stop", "service")
            restore_started = time.monotonic()
            restore_new(archive, target, image, None, None)
            verify(target, kind)
            result = {"service": kind, "image": meta["image"], "imageId": meta["imageId"], "bytes": meta["bytes"],
                      "sha256": meta["sha256"], "backupAt": meta["createdAt"], "businessVerified": True,
                      "restoreSeconds": round(time.monotonic() - restore_started, 2)}
        finally:
            try:
                cleanup(target)
            finally:
                cleanup(source)
    result.update({"removedContainersVolumesNetworks": True, "totalSeconds": round(time.monotonic() - started, 2)})
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=IMAGES)
    parser.add_argument("--image")
    options = parser.parse_args()
    drill(options.service, options.image)
