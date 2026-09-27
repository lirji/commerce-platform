#!/usr/bin/env python3
"""OLD df955f1 :8625 reads/consumes NEW :8623 quote with extended trace OFF.

Only isolated benchmark identities are read. An inventory receipt and real order
are written to that synthetic tenant; no credential is printed.
"""
import json
from pathlib import Path
import uuid
from datetime import datetime, timedelta, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError

access = json.loads(Path(".local/phase7-bench/tokens.json").read_text())["small"]


def call(port, path, role="member", body=None, method="POST"):
    headers = {"Authorization": "Bearer " + access[role], "Idempotency-Key": str(uuid.uuid4())}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request(f"http://127.0.0.1:{port}" + path,
        data=None if method == "GET" else b"" if body is None else json.dumps(body).encode(),
        headers=headers, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            assert response.status == 200
            return json.loads(response.read())
    except HTTPError as error:
        payload = json.loads(error.read())
        raise RuntimeError(f"{path}: HTTP {error.code}, {payload.get('message')}") from None


# 候选种子只用于查询规模，不包含预算写权威；交易兼容使用真实API创建完整活动。
ident = "roll-quote-" + uuid.uuid4().hex[:8]
call(8623, "/v1/admin/stores", "admin", {"storeId": ident, "merchantId": "merchant1", "name": ident})
call(8623, "/v1/admin/skus", "admin", {"skuId": ident, "storeId": ident, "title": ident, "unitPrice": "25.00"})
call(8623, "/v1/admin/inventory/receipts", "admin", {"storeId": ident, "skuId": ident, "quantity": 1})
now = datetime.now(timezone.utc)
for suffix in ("a", "b"):
    campaign = ident + suffix
    call(8623, "/v1/admin/campaigns", "admin", {"campaignId": campaign, "version": 1,
        "storeId": ident, "name": campaign, "validFrom": (now-timedelta(minutes=1)).isoformat(),
        "validTo": (now+timedelta(hours=1)).isoformat(), "minimumSpend": "20.00", "discountAmount": "1.00",
        "rule": {"kind": "COMPARE", "field": "memberLevel", "operator": "EQ", "valueType": "TEXT", "value": "VIP"}})
    call(8623, "/v1/admin/campaigns/" + campaign + "/1/publish", "admin", {"expectedVersion": 0})
quote = call(8623, "/v1/quotes", body={"storeId": ident, "items": [{"skuId": ident, "quantity": 1}]})
assert len(quote["trace"]) == 2
assert all(t["reason"] == "ELIGIBLE" for t in quote["trace"])
old_view = call(8625, "/v1/quotes/" + quote["quoteId"], method="GET")
assert old_view["campaign"] == quote["campaign"] and old_view["payable"] == quote["payable"]
order = call(8625, "/v1/orders", body={"quoteId": quote["quoteId"],
    "address": {"recipient": "兼容验证", "phone": "13800000000", "detail": "隔离合成订单"}})
assert order["payable"] == quote["payable"]
call(8625, "/v1/orders/" + order["orderId"] + "/cancel")
print("PASS: NEW multi-candidate quote with extended trace OFF -> OLD read 200 -> OLD order 200 -> cancel 200; winner/payable unchanged.")
