#!/usr/bin/env python3
"""创建独占的版本化对象存储及受限角色，仅用于 CI 的归档验收。"""

import json
import os
import secrets
import subprocess
import time
from urllib.request import urlopen

PROJECT = "commerce"
IMAGE = "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"

if os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("该脚本仅用于隔离的 GitHub CI 运行器。")
run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
if not run_id.isdigit() or not attempt.isdigit():
    raise SystemExit("无效的隔离 CI 运行编号。")
container = f"{PROJECT}-ci-audit-{run_id}-{attempt}"


def request(args, data=None, env=None, timeout=30):
    try:
        result = subprocess.run(args, input=data, env=env, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise SystemExit("CI 归档夹具等待超时，敏感参数已隐藏。") from None
    if result.returncode != 0:
        raise SystemExit("CI 归档夹具初始化失败，凭据和命令参数已隐藏。")
    return result.stdout.strip()


def mc(*args):
    return request(["docker", "exec", container, "mc", *args])


def grant(role, statements):
    key, secret = role.lower().replace("_", "-") + "-" + secrets.token_hex(4), secrets.token_hex(24)
    path = "/tmp/audit-policy-" + role.lower() + ".json"
    document = json.dumps({"Version": "2012-10-17", "Statement": statements})
    request(["docker", "exec", "-i", container, "sh", "-c", "cat > " + path], data=document)
    mc("admin", "policy", "create", "fixture", role, path)
    mc("admin", "user", "add", "fixture", key, secret)
    mc("admin", "policy", "attach", "fixture", role, "--user", key)
    return key, secret


def statement(actions, resource, condition=None):
    value = {"Effect": "Allow", "Action": actions, "Resource": [resource]}
    if condition:
        value["Condition"] = condition
    return value


root_user = "audit-fixture-" + secrets.token_hex(4)
root_password = secrets.token_hex(24)
with open(os.environ["GITHUB_ENV"], "a") as output:
    output.write("AUDIT_ARCHIVE_CI_CONTAINER=" + container + "\n")
request(["docker", "run", "--detach", "--name", container, "--publish", "127.0.0.1::9000",
         "--env", "MINIO_ROOT_USER", "--env", "MINIO_ROOT_PASSWORD", IMAGE, "server", "/data"],
        env=dict(os.environ, MINIO_ROOT_USER=root_user, MINIO_ROOT_PASSWORD=root_password), timeout=180)
endpoint = "http://" + request(["docker", "port", container, "9000"])
for _ in range(40):
    try:
        with urlopen(endpoint + "/minio/health/ready", timeout=2):
            break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit("CI 版本化对象存储没有就绪。")
mc("alias", "set", "fixture", "http://127.0.0.1:9000", root_user, root_password)

bucket = "commerce-audit-lock-verification"
mc("mb", "--with-lock", "fixture/" + bucket)
arn = "arn:aws:s3:::" + bucket
base = "commerce/operation-audit/v1/"
write_key, write_secret = grant("commerce_audit_write", [
    statement(["s3:GetBucketVersioning"], arn),
    statement(["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"], f"{arn}/{base}*"),
])
restore_key, restore_secret = grant("commerce_audit_restore", [
    statement(["s3:GetObjectVersion"], f"{arn}/{base}*"),
    statement(["s3:GetObject"], f"{arn}/{base}recovery/*"),
    statement(["s3:ListBucket"], arn, {"StringLike": {"s3:prefix": [f"{base}recovery/*"]}}),
])
expiry_key, expiry_secret = grant("commerce_audit_expiry", [
    statement(["s3:GetObjectVersion", "s3:DeleteObject", "s3:DeleteObjectVersion"],
              f"{arn}/{base}????????-????-????-????-????????????.ndjson.gz"),
])
values = {"ARCHIVE_TEST_ENDPOINT": endpoint, "ARCHIVE_TEST_BUCKET": bucket,
          "ARCHIVE_WRITE_KEY": write_key, "ARCHIVE_WRITE_SECRET": write_secret,
          "ARCHIVE_RESTORE_KEY": restore_key, "ARCHIVE_RESTORE_SECRET": restore_secret,
          "ARCHIVE_EXPIRY_KEY": expiry_key, "ARCHIVE_EXPIRY_SECRET": expiry_secret,
          "MINIO_ROOT_USER": root_user, "MINIO_ROOT_PASSWORD": root_password}

with open(os.environ["GITHUB_ENV"], "a") as output:
    for name, value in values.items():
        output.write(f"{name}={value}\n")
print("CI 版本化归档夹具和独立角色已就绪。")
