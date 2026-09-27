#!/usr/bin/env python3
"""Two real nodes, 120 distinct buyers/provider, quota 30, isolated benchmark DB.

Reads ignored local bootstrap tokens; stores generated credentials only in .local.
Uses Docker root solely to provision test credentials and read lock counters.
No shared middleware is restarted and no data is deleted.
"""
import argparse
import concurrent.futures as futures
import hashlib
import json
import pathlib
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError

DB = "commerce_phase7_bench"
ADMIN = json.loads(pathlib.Path(".local/phase7-bench/tokens.json").read_text())["small"]["admin"]
TENANT = "phase7-small"
RUN = uuid.uuid4().hex[:8]
PORTS = (8623, 8624)


def sql(query):
    return subprocess.check_output(["docker", "exec", "-i", "dev-infra-mysql84-1", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ' + DB + ' -N'], input=query.encode()).decode().strip()


def request(path, token=ADMIN, body=None, port=8623, method="POST"):
    headers = {"Authorization": "Bearer " + token, "Idempotency-Key": str(uuid.uuid4())}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = Request(f"http://127.0.0.1:{port}" + path,
        data=None if method == "GET" else b"" if body is None else json.dumps(body).encode(),
        headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urlopen(req, timeout=30) as response:
            return response.status, json.loads(response.read()), (time.perf_counter()-started)*1000
    except HTTPError as error:
        return error.code, json.loads(error.read()), (time.perf_counter()-started)*1000


def post(path, body=None, token=ADMIN, port=8623):
    status, data, duration = request(path, token, body, port)
    if status != 200:
        raise RuntimeError(f"{path}: HTTP {status} {data}")
    return data, duration


def when(seconds):
    return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def summary(stage, kind, durations, success, conflict=0, elapsed=None):
    durations = sorted(durations)
    def p(q):
        return durations[round((len(durations)-1)*q)]
    print(f"{kind},{stage},{len(durations)},{success},{conflict},{p(.5):.2f},{p(.95):.2f},"
          f"{p(.99):.2f},{len(durations)/elapsed:.1f}" if elapsed else
          f"{kind},{stage},{len(durations)},{success},{conflict},{p(.5):.2f},{p(.95):.2f},{p(.99):.2f},na", flush=True)


def provider(kind):
    ident = f"load-{kind}-{RUN}"
    post("/v1/admin/stores", {"storeId": ident, "merchantId": "merchant1", "name": ident})
    post("/v1/admin/skus", {"skuId": ident, "storeId": ident, "title": ident, "unitPrice": "25.00"})
    post("/v1/admin/inventory/receipts", {"storeId": ident, "skuId": ident, "quantity": 150})
    buyers = []
    credential_sql = []
    for n in range(120):
        actor = f"{ident}-{n}"
        token = str(uuid.uuid4())+str(uuid.uuid4())
        post("/v1/admin/members", {"memberId": actor, "actorId": actor, "displayName": "配额压测", "memberLevel": "VIP"})
        digest = hashlib.sha256(token.encode()).hexdigest()
        credential_sql.append(f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','{TENANT}','{actor}','MEMBER','2030-01-01');")
        buyers.append((actor, token, PORTS[n % 2]))
    sql("\n".join(credential_sql))
    pathlib.Path(f".local/phase7-bench/{ident}-tokens.json").write_text(json.dumps(buyers))
    post("/v1/admin/audiences", {"audienceId": ident, "version": 1, "name": ident, "source": "phase7-load",
        "watermark": when(-1), "validUntil": when(6000), "memberIds": [b[0] for b in buyers]})
    definition = {"version": 1, "storeId": ident, "name": ident, "quota": 30,
        "validFrom": when(-120), "validTo": when(7200), "validityDays": 1}
    terms = {"percentageBps": 0, "platformFundingBps": 0, "budget": "1000.00"}
    if kind == "credit":
        definition.update(benefitId=ident, units=3)
        post("/v1/admin/entitlement-definitions", definition)
        terms["grant"] = {"benefitId": ident, "version": 1}
    else:
        definition.update(definitionId=ident, minimumSpend="0.00", discountAmount="2.00", stackable=False, issuanceMode="SOURCE_ONLY")
        post("/v1/admin/coupon-definitions", definition)
        terms["coupon"] = {"definitionId": ident, "version": 1}
    post("/v1/admin/campaigns", {"campaignId": ident, "version": 1, "storeId": ident, "name": ident,
        "validFrom": when(-60), "validTo": when(3600), "minimumSpend": "20.00", "discountAmount": "1.00",
        "rule": {"kind": "COMPARE", "field": "memberLevel", "operator": "EQ", "valueType": "TEXT", "value": "VIP"},
        "policy": {"audience": {"id": ident, "version": 1}, "terms": terms}})
    for version, action in enumerate(("submit", "approve", "publish")):
        post(f"/v1/admin/campaigns/{ident}/1/{action}", {"expectedVersion": version})
    def quote(buyer):
        _, token, port = buyer
        data, duration = post("/v1/quotes", {"storeId": ident, "items": [{"skuId": ident, "quantity": 1}]}, token, port)
        return buyer, data, duration
    with futures.ThreadPoolExecutor(max_workers=12) as pool:
        quotes = list(pool.map(quote, buyers))
    summary("quote", kind, [q[2] for q in quotes], 120)
    def order(q):
        buyer, data, _ = q
        _, token, port = buyer
        return buyer, request("/v1/orders", token, {"quoteId": data["quoteId"],
            "address": {"recipient": "压测", "phone": "13800000000", "detail": "隔离地址"}}, port)
    started = time.perf_counter()
    with futures.ThreadPoolExecutor(max_workers=12) as pool:
        orders = list(pool.map(order, quotes))
    successes = [o for o in orders if o[1][0] == 200]
    conflicts = sum(o[1][0] == 409 for o in orders)
    summary("order_reservation", kind, [o[1][2] for o in orders], len(successes), conflicts, time.perf_counter()-started)
    assert len(successes) == 30 and conflicts == 90, [(o[1][0], o[1][1]) for o in orders if o[1][0] not in (200,409)]
    def payment(o):
        buyer, (_, data, _) = o
        _, token, port = buyer
        payment, _ = post(f"/v1/orders/{data['orderId']}/payments", token=token, port=port)
        post(f"/v1/admin/sandbox/payments/{payment['paymentId']}/fact", {"status": "PAID"}, port=port)
        started = time.perf_counter()
        _, duration = post(f"/v1/orders/{data['orderId']}/payment/reconcile", token=token, port=port)
        return data["orderId"], started, duration
    with futures.ThreadPoolExecutor(max_workers=12) as pool:
        paid = list(pool.map(payment, successes))
    summary("payment_reconcile", kind, [p[2] for p in paid], 30)
    started = time.perf_counter()
    for _ in range(200):
        with futures.ThreadPoolExecutor(max_workers=2) as pool:
            pumps = list(pool.map(lambda port: post("/v1/admin/events/pump", port=port)[0], PORTS))
        # 双实例 SKIP LOCKED/时间预算可能暂时返回零，必须用业务结果判断排空。
        completed = sql(f"SELECT COUNT(*) FROM marketing_execution WHERE tenant_id='{TENANT}' AND campaign_id='{ident}' AND status='GRANTED'")
        if completed == "30":
            break
    print(f"{kind},event_pump_drain_seconds,{time.perf_counter()-started:.3f}", flush=True)
    latencies = []
    for order_id, started, _ in paid:
        status, view, _ = request(f"/v1/admin/marketing-executions/{order_id}/{ident}", method="GET")
        assert status == 200 and view["status"] == "GRANTED", view
        latencies.append((time.perf_counter()-started)*1000)
    summary("payment_to_observed_granted_upper_bound", kind, latencies, 30)
    table = "benefit_definition" if kind == "credit" else "benefit_coupon_definition"
    column = "benefit_id" if kind == "credit" else "definition_id"
    assert sql(f"SELECT CONCAT(reserved,',',issued) FROM {table} WHERE tenant_id='{TENANT}' AND {column}='{ident}'") == "0,30"
    granted_table = "benefit_grant" if kind == "credit" else "benefit_coupon"
    granted_column = "benefit_id" if kind == "credit" else "definition_id"
    assert sql(f"SELECT COUNT(*) FROM {granted_table} WHERE tenant_id='{TENANT}' AND {granted_column}='{ident}'") == "30"
    print(f"{kind},invariant,reserved=0 issued=30 grants=30 orders=30 conflicts=90", flush=True)


print("provider,stage,samples,success,conflict,p50_ms,p95_ms,p99_ms,throughput_per_second", flush=True)
parser = argparse.ArgumentParser()
parser.add_argument("--provider", choices=("credit", "coupon", "both"), default="both")
args = parser.parse_args()
query = "SELECT NAME,COUNT FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks','lock_timeouts','lock_row_lock_waits','lock_row_lock_time')"
before = sql(query)
for kind in (("credit", "coupon") if args.provider == "both" else (args.provider,)):
    provider(kind)
after = sql(query)
print("lock_counters_before\n"+before+"\nlock_counters_after\n"+after, flush=True)
