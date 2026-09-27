#!/usr/bin/env python3
"""Bootstrap API identities/catalog only in the dedicated Phase 7 benchmark DB.

Run after the application has migrated commerce_phase7_bench to V43.
Generated tokens remain in ignored .local with mode 0600; never printed.
The existing bootstrap is reused; this script never deletes or resets data.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid
from urllib.request import Request, urlopen

path = Path(".local/phase7-bench/tokens.json")
if path.exists():
    print("Existing private benchmark bootstrap reused.")
    raise SystemExit(0)
tokens = {tier: {"admin": secrets.token_hex(32), "member": secrets.token_hex(32)}
          for tier in ("small", "medium", "large")}
statements = []
for tier, access in tokens.items():
    for role, token in access.items():
        actor = "admin" if role == "admin" else "buyer"
        digest = hashlib.sha256(token.encode()).hexdigest()
        statements.append(f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) "
            f"VALUES('{digest}','phase7-{tier}','{actor}','{role.upper()}','2030-01-01');")
result = subprocess.run(["docker", "exec", "-i", "dev-infra-mysql84-1", "sh", "-c",
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot commerce_phase7_bench'],
    input="\n".join(statements).encode(), capture_output=True)
if result.returncode:
    raise SystemExit("Isolated benchmark credential bootstrap failed; sensitive details suppressed.")


def post(token, endpoint, data):
    request = Request("http://127.0.0.1:8623" + endpoint, data=json.dumps(data).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Idempotency-Key": str(uuid.uuid4())}, method="POST")
    with urlopen(request, timeout=20) as response:
        assert response.status == 200


for tier, access in tokens.items():
    admin = access["admin"]
    post(admin, "/v1/admin/members", {"memberId": "m000010", "actorId": "buyer", "displayName": "基准会员", "memberLevel": "VIP"})
    post(admin, "/v1/admin/merchants", {"merchantId": "merchant1", "name": "基准商家"})
    post(admin, "/v1/admin/stores", {"storeId": "store1", "merchantId": "merchant1", "name": "基准店铺"})
    post(admin, "/v1/admin/skus", {"skuId": "sku1", "storeId": "store1", "title": "基准商品", "unitPrice": "25.00"})
path.parent.mkdir(parents=True, exist_ok=True)
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as file:
    json.dump(tokens, file)
print("API fixture created in isolated benchmark tenants; private tokens saved without printing.")
