"""Run the actual installer in a disposable, offline Linux container."""
import argparse
import io
from pathlib import Path
import shlex
import subprocess
import tarfile
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = r"""
set -euo pipefail
test -f /.dockerenv
test ! -e /etc/cloide/server.env
mkdir -p /etc/systemd/system /tmp/fixture-bin
cat > /tmp/fixture-bin/systemctl <<'STUB'
#!/bin/sh
set -eu
echo "$*" >> /tmp/systemctl.log
case "$1" in
    daemon-reload|enable|status) ;;
    is-active) test -e /tmp/cloide-active ;;
    start|restart) touch /tmp/cloide-active ;;
    *) exit 70 ;;
esac
STUB
chmod 0755 /tmp/fixture-bin/systemctl
export PATH="/tmp/fixture-bin:$PATH"
install_it() {
    JAR_PATH="/tmp/fixture/$2.jar" RELEASE_ID="$1" bash /tmp/fixture/deploy/server/install.sh
}
umask 0022
install_it first first
test "$(stat -c '%a %U %G' /etc/cloide)" = '700 root root'
test "$(stat -c '%a %U %G' /etc/cloide/server.env)" = '600 root root'
test "$(stat -c '%a %U %G' /var/lib/cloide)" = '700 cloide cloide'
cmp /tmp/fixture/deploy/server/cloide-server.env.example /etc/cloide/server.env
cmp /tmp/fixture/deploy/server/cloide-server.service /etc/systemd/system/cloide-server.service
test "$(cat /tmp/systemctl.log)" = 'daemon-reload'
test ! -e /tmp/cloide-active
echo 'PASS private template; no service start'

live=/opt/cloide/server/cloide-server.jar
target=/opt/cloide/releases
basename=cloide-server-0.0.1-SNAPSHOT.jar
test "$(readlink "$live")" = "$target/first/$basename"
sed -i 's/REPLACE_WITH_[A-Z0-9_]\+/isolated_fixture_value/g' /etc/cloide/server.env
cp /etc/cloide/server.env /tmp/configured.env
: > /tmp/systemctl.log
install_it first first
expected() {
    printf '%s\n' 'daemon-reload' 'enable cloide-server' 'is-active --quiet cloide-server' \
        "$1 cloide-server" 'status cloide-server --no-pager'
}
test "$(cat /tmp/systemctl.log)" = "$(expected start)"
test -e /tmp/cloide-active
echo 'PASS configured reinstall requests service start'

: > /tmp/systemctl.log
if install_it first second > /tmp/conflict.log 2>&1; then exit 1; fi
grep -Fq 'release identifier already belongs to a different JAR' /tmp/conflict.log
test ! -s /tmp/systemctl.log
test "$(readlink "$live")" = "$target/first/$basename"
cmp /tmp/fixture/first.jar "$live"
echo 'PASS same ID with different JAR rejected before mutation'

install_it second second
test "$(cat /tmp/systemctl.log)" = "$(expected restart)"
test "$(readlink "$live")" = "$target/second/$basename"
cmp /tmp/fixture/second.jar "$live"
cmp /tmp/fixture/first.jar "$target/first/$basename"
cmp /etc/cloide/server.env /tmp/configured.env
test "$(stat -c '%a %U %G' /etc/cloide/server.env)" = '600 root root'
echo 'PASS active upgrade switches JAR and requests service restart'
"""


def payload():
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w") as archive:
        for filename in ("install.sh", "cloide-server.service", "cloide-server.env.example"):
            path = Path("deploy/server") / filename
            archive.add(ROOT / path, arcname=path.as_posix())
        files = {"check.sh": FIXTURE.encode()}
        for release in ("first", "second"):
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w") as bundle:
                bundle.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
                bundle.writestr("version", release)
            files[release + ".jar"] = jar.getvalue()
        for name, data in files.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="rabbitmq:3.13-management", help="cached image with bash, useradd and GNU coreutils")
    parser.add_argument("--ssh", help="optional SSH alias for the Linux Docker host")
    args = parser.parse_args()
    name = "cloide-installer-check-" + uuid.uuid4().hex

    def docker(*parts, input_data=None):
        cmd = ["docker", *parts]
        if args.ssh:
            cmd = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "--", args.ssh, shlex.join(cmd)]
        return subprocess.run(cmd, input=input_data, capture_output=True, timeout=90, check=True).stdout.decode()

    image = docker("image", "inspect", "--format", "{{.Id}}", args.image).strip()
    output = docker("run", "--rm", "--pull=never", "--network=none", "--security-opt", "no-new-privileges",
                    "--pids-limit", "64", "--memory", "160m", "--user", "0:0", "--name", name,
                    "-i", "--entrypoint", "/bin/bash", image, "-ec",
                    "mkdir -m 700 /tmp/fixture && tar xf - -C /tmp/fixture && bash /tmp/fixture/check.sh",
                    input_data=payload())
    print(output, end="")
    if docker("container", "ls", "-aq", "--filter", f"name=^/{name}$").strip():
        raise RuntimeError(f"fixture container still exists: {name}")
    print("PASS installer container removed; image", image)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        print(exc.stdout.decode(errors="replace"))
        print(exc.stderr.decode(errors="replace")[-1200:])
        raise SystemExit(exc.returncode)
