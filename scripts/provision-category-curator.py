#!/usr/bin/env python3
"""为本机商城签发独立的类目专岗凭据，平台运维账号的既有权限保持原样。"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import tempfile
from urllib.parse import urlsplit


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", choices=("commerce_local", "commerce_test_20260923"), required=True)
    parser.add_argument("--mysql-container", default="dev-infra-mysql84-1")
    parser.add_argument("--base-url", default="http://127.0.0.1:8602")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    target_url = urlsplit(args.base_url)
    if target_url.scheme != "http" or target_url.hostname != "127.0.0.1" or target_url.port is None:
        parser.error("仅支持本机回环 HTTP 入口")

    os.umask(0o077)
    output = args.output.resolve()
    if output.exists():
        parser.error("凭据文件已存在，请使用独立路径签发")
    output.parent.mkdir(parents=True, exist_ok=True)
    settings = {}
    for line in (root / ".local/runtime.env").read_text().splitlines():
        key, _, raw = line.removeprefix("export ").partition("=")
        if key in ("COMMERCE_DB_USER", "COMMERCE_DB_PASSWORD"):
            settings[key] = shlex.split(raw)[0]

    actor = "category-curator-" + secrets.token_hex(8)
    token = secrets.token_hex(32)
    digest = hashlib.sha256(token.encode()).hexdigest()
    expires = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=30)
    expires_sql = expires.strftime("%Y-%m-%d %H:%M:%S")
    sql = f"""SET time_zone = '+00:00';
START TRANSACTION;
INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at)
VALUES('{digest}','platform','{actor}','PLATFORM_OPERATOR','{expires_sql}');
INSERT INTO platform_category_curator(tenant_id,actor_id) VALUES('platform','{actor}');
COMMIT;
"""
    # 凭据先写入同目录私密临时文件，事务成功后只发布到空路径。
    with tempfile.NamedTemporaryFile(mode="w", dir=output.parent) as prepared:
        json.dump({
            "baseUrl": args.base_url,
            "actorId": actor,
            "platformToken": token,
            "expiresAt": expires.isoformat(),
        }, prepared, ensure_ascii=False, indent=2)
        prepared.write("\n")
        prepared.flush()
        attempt = subprocess.run(
            ["docker", "exec", "-i", "-e", "MYSQL_PWD", args.mysql_container,
             "mysql", "-u" + settings["COMMERCE_DB_USER"], args.schema],
            input=sql, text=True, capture_output=True, timeout=20,
            env=dict(os.environ, MYSQL_PWD=settings["COMMERCE_DB_PASSWORD"]),
        )
        if attempt.returncode:
            raise SystemExit("签发失败，请确认 V84 与本机数据库配置，凭据未输出。")
        os.link(prepared.name, output)
    print("平台类目维护凭据文件", output)


if __name__ == "__main__":
    main()
