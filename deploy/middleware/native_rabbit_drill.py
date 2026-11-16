"""RabbitMQ package exercise for dedicated, unprivileged Linux PID/network namespaces without Docker."""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import struct
import subprocess
import tempfile
import time
from urllib.error import URLError

from instance import ADMIN, management_request, rabbit_definitions, write_json


@contextmanager
def broker(parent, name, package, offset):
    home = parent / name
    home.mkdir(mode=0o700)
    admin_pw = secrets.token_hex(24)
    port = 21672 + offset
    config = home / "rabbitmq.conf"
    config.write_text(f"""listeners.tcp.1 = 127.0.0.1:{port}
management.tcp.ip = 127.0.0.1
management.tcp.port = {port+1}
distribution.listener.interface = 127.0.0.1
vm_memory_calculation_strategy = erlang
vm_memory_high_watermark.absolute = 384MiB
default_user = {ADMIN}
default_pass = {admin_pw}
default_vhost = orders
""")
    (home / "enabled_plugins").write_text("[rabbitmq_management].\n")
    (home / ".erlang.cookie").write_text(secrets.token_hex(24))
    (home / ".erlang.cookie").chmod(0o400)
    env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": str(home), "LANG": "C.UTF-8",
           "RABBITMQ_NODENAME": name+"@localhost", "RABBITMQ_DIST_PORT": str(port+2),
           "RABBITMQ_CONFIG_FILE": str(config), "RABBITMQ_CONF_ENV_FILE": str(home / "unused-env"),
           "RABBITMQ_MNESIA_BASE": str(home / "db"), "RABBITMQ_LOG_BASE": str(home / "log"),
           "RABBITMQ_PID_FILE": str(home / "rabbit.pid"), "RABBITMQ_PLUGINS_EXPAND_DIR": str(home / "plugins"),
           "RABBITMQ_ENABLED_PLUGINS_FILE": str(home / "enabled_plugins"), "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS": "+S 2:2 +A 2",
           "ERL_EPMD_PORT": str(port+3), "ERL_EPMD_ADDRESS": "127.0.0.1", "ERL_CRASH_DUMP": str(home / "erl.dump")}
    def api(path, method="GET", body=None):
        return management_request(f"127.0.0.1:{port+1}", admin_pw, path, method, body)
    with (home/"console.log").open("w") as log:
        proc = subprocess.Popen([str(package / "sbin/rabbitmq-server")], cwd=home, env=env,
                                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic()+55
            while True:
                try:
                    info = api("overview")
                    break
                except (OSError, URLError):
                    if proc.poll() is not None or time.monotonic() >= deadline:
                        # Log is private; omit credentials if any were printed by the packaged launcher.
                        detail = (home / "console.log").read_text(errors="replace")[-1800:].replace(admin_pw, "[redacted]")
                        raise RuntimeError("隔离 RabbitMQ 未就绪：" + detail)
                    time.sleep(.5)
            api("definitions", "POST", rabbit_definitions(admin_pw, secrets.token_hex(32)))
            yield api, info["rabbitmq_version"]
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=5)
            subprocess.run(["epmd", "-kill"], env=env, capture_output=True, timeout=5)


def publish(api, event):
    routed = api("exchanges/orders/orders.events/publish", "POST", {"routing_key": "order.changed",
                 "properties": {"message_id": event, "delivery_mode": 2}, "payload": event, "payload_encoding": "string"})
    assert routed["routed"]


def get(api, mode):
    return api("queues/orders/orders.consumer/get", "POST",
               {"count": 10, "ackmode": mode, "encoding": "auto", "truncate": 128})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, help="RabbitMQ package root containing sbin and plugins")
    args = parser.parse_args()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        active = set()
        for _, name in socket.if_nameindex():
            request = struct.pack("256s", name.encode()[:15])
            flags = struct.unpack_from("H", fcntl.ioctl(sock, 0x8913, request), 16)[0]
            if flags & 1:
                active.add(name)
    if os.geteuid() == 0 or active != {"lo"}:
        raise ValueError("本演练要求没有外网接口的独立网络命名空间和非 root 身份")
    with tempfile.TemporaryDirectory(prefix="rabbit-drill-") as folder:
        parent = Path(folder)
        with broker(parent, "source", args.package, 0) as (api, version):
            publish(api, "order-119")
            snapshot = api("definitions/orders")
            write_json(parent / "topology.json", snapshot)
        started = time.monotonic()
        with broker(parent, "target", args.package, 10) as (api, _version):
            api("definitions/orders", "POST", json.loads((parent / "topology.json").read_text()))
            assert not get(api, "ack_requeue_false")
            assert api("queues/orders/orders.consumer")["arguments"]["x-dead-letter-exchange"] == "orders.dead"
            for event in ["order-119", "order-119", "order-120"]:
                publish(api, event)
            assert len(get(api, "ack_requeue_true")) == 3
            replayed = get(api, "ack_requeue_false")
            assert all(row["redelivered"] for row in replayed)
            assert {row["properties"]["message_id"] for row in replayed} == {"order-119", "order-120"}
            assert not get(api, "ack_requeue_false")
            print(json.dumps({"service":"rabbitmq", "version":version, "architecture":os.uname().machine,
                              "topologyRestored":True, "ackAndReplayVerified":True,
                              "restoreSeconds":round(time.monotonic()-started, 2)}))


if __name__ == "__main__":
    main()
