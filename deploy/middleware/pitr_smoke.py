"""Restore a managed PostgreSQL cluster between two committed business transactions."""
import argparse
import json
import os
from pathlib import Path
import tempfile
import time

from instance import IMAGES, cli, initialize
from postgres_pitr import base, enable, point, query, recover
from smoke import cleanup, sql


def drill(image):
    with tempfile.TemporaryDirectory(prefix="cloide-pitr-") as tmp:
        root = Path(tmp)
        source, target, vault = root/"source",root/"restored",root/"archive"
        try:
            initialize(source,"postgres",image)
            info = enable(source,vault)
            sql(source,"CREATE TABLE orders(id integer PRIMARY KEY, state text NOT NULL);")
            b = base(source)
            sql(source,"BEGIN; INSERT INTO orders VALUES(301,'paid'); COMMIT;")
            mark = point(source,"after_payment")
            sql(source,"BEGIN; INSERT INTO orders VALUES(302,'shipped'); COMMIT;")
            # Archive the later transaction as well, proving data exclusion by exact recovery target.
            after = point(source,"after_shipping")
            select = "SELECT string_agg(id::text,',' ORDER BY id) FROM orders"
            if sql(source,select)!="301,302": raise AssertionError("源实例缺少已提交订单")
            started = time.monotonic()
            proof = recover(target,vault,b["id"],mark["name"])
            if sql(target,select)!="301": raise AssertionError("恢复点后的事务出现在目标，或恢复点前订单丢失")
            sql(target,"INSERT INTO orders VALUES(303,'verified');")
            if sql(source,select)!="301,302" or sql(target,select)!="301,303":
                raise AssertionError("独立目标事务核验失败")
            result = {**proof,"version":query(source,"SHOW server_version"),"archiveMode":info["archiveMode"],
                      "baseEnd":b["end"],"pointLsn":mark["lsn"],"laterArchivedLsn":after["lsn"],
                      "sourceOrders":[301,302],"restoredOrders":[301],"newTargetOrder":303,
                      "restoreSeconds":round(time.monotonic()-started,2)}
        finally:
            try: cleanup(target)
            finally:
                cleanup(source)
                # The disposable WAL directory belongs to the image's postgres UID.
                if os.geteuid()!=0 and (vault/"wal").is_dir():
                    cli("docker","run","--rm","--network","none","-v",str(vault/"wal")+":/wal",
                        "--entrypoint","chown",image or IMAGES["postgres"],"-R",
                        f"{os.geteuid()}:{os.getegid()}","/wal")
    print(json.dumps({**result,"temporaryFilesContainersNetworksVolumesRemoved":True}))


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image")
    drill(parser.parse_args().image)
