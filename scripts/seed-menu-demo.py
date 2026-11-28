#!/usr/bin/env python3
"""补齐本机 demo 租户各菜单的关联演示数据，或只验证现有数据。"""

import argparse
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
STORE = "store-demo"
MERCHANT = "menu-roastery"
OPENING = "menu-riverside"
OPERATOR = "menu-store-clerk"
MEMBER = "member-demo"
GOLD_MEMBER = "menu-coffee-member"
PRODUCT = "menu-drip-coffee"
SKU = "menu-drip-coffee-box"
TAG = "menu-coffee"
RULE = "menu-coffee-rule"
SEGMENT = "menu-coffee-segment"
AUDIENCE = "menu-coffee-audience"
CAMPAIGN = "menu-coffee-week"
COUPON = "menu-coffee-coupon"
DELIVERY = "menu-coffee-delivery"
BENEFIT = "menu-coffee-benefit"
OFFER = "menu-coffee-exchange"
JOURNEY = "menu-birthday"
JOB = "menu-coffee-reprice"
REPLAY = "menu-projection"
PLATFORM_CATEGORY = "DEMO_COFFEE_COCOA"
OPS_PAGE = "menu-coffee-operations"
REASON = "本地商城菜单演示"
DEMO_GROWTH_REASON = "演示会员成长入账"
LEVELS = [
    {"code": "BASIC", "minimumGrowth": 0},
    {"code": "GOLD", "minimumGrowth": 100},
    {"code": "VIP", "minimumGrowth": 500},
]


class NoRedirect(HTTPRedirectHandler):
    """演示身份只发送到明确指定的本机 API。"""

    def redirect_request(self, *args, **kwargs):
        return None


class DemoApi:
    def __init__(self, base, access):
        self.base = base
        self.access = access
        self.opener = build_opener(NoRedirect())

    def request(self, path, *, identity="adminToken", method="GET", body=None, key=None):
        headers = {"Authorization": "Bearer " + self.access[identity]}
        data = None
        if method == "POST":
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if key:
            headers["Idempotency-Key"] = "menu-demo-" + key
        request = Request(self.base + "/v1" + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                code = json.load(error).get("code", "UNKNOWN")
            except (ValueError, AttributeError):
                code = "UNKNOWN"
            raise SystemExit(f"{method} {path} HTTP {error.code} ({code})") from None

    def get(self, path, identity="adminToken"):
        return self.request(path, identity=identity)

    def post(self, path, key, body=None, identity="adminToken"):
        return self.request(path, identity=identity, method="POST", body=body, key=key)

    def publish(self, family, identifier):
        for version, action in enumerate(("submit", "approve", "publish")):
            self.post(f"/admin/{family}/{identifier}/1/{action}", f"{identifier}-{action}",
                      {"expectedVersion": version})

    def validate_target(self):
        for identity, actor, role in (
            ("adminToken", "demo-admin", "ADMIN"),
            ("memberToken", "demo-buyer", "MEMBER"),
        ):
            who = self.get("/me", identity)
            if (who["tenantId"], who["actorId"], who["role"]) != ("demo", actor, role):
                raise SystemExit("请使用 seed-local.py 创建的 demo 管理员和会员凭据。")
        if not self.get("/runtime-capabilities")["sandboxEnabled"]:
            raise SystemExit("演示数据需要本机沙箱。")
        if not any(row["storeId"] == STORE for row in self.get("/admin/stores")):
            raise SystemExit("请先用 seed-local.py 创建演示门店。")


def seed_clock(path):
    # 固定有效时间，防止重跑时同一幂等键携带不同的请求。
    try:
        with path.open("x") as handle:
            handle.write(datetime.now(timezone.utc).replace(microsecond=0).isoformat() + "\n")
    except FileExistsError:
        pass
    return datetime.fromisoformat(path.read_text().strip())


def provision_operator(api, fixture_dir):
    # 仅身份摘要沿用本地种子惯例写认证表，授权记录仍走业务接口。
    config = {}
    for line in (fixture_dir.parent / "runtime.env").read_text().splitlines():
        key, _, raw = line.removeprefix("export ").partition("=")
        if key in ("COMMERCE_DB_URL", "COMMERCE_DB_USER", "COMMERCE_DB_PASSWORD"):
            config[key] = shlex.split(raw)[0]
    if "/commerce_local?" not in config["COMMERCE_DB_URL"]:
        raise SystemExit("演示门店运营凭据只写入 commerce_local。")
    path = fixture_dir / "operator-access.json"
    if not path.exists():
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump({"operatorToken": secrets.token_hex(32)}, handle)
    token = json.loads(path.read_text())["operatorToken"]
    digest = hashlib.sha256(token.encode()).hexdigest()
    sql = (
        "INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) "
        f"VALUES('{digest}','demo','{OPERATOR}','OPERATOR',UTC_TIMESTAMP()+INTERVAL 30 DAY) "
        "ON DUPLICATE KEY UPDATE expires_at=UTC_TIMESTAMP()+INTERVAL 30 DAY;"
    )
    result = subprocess.run(
        ["docker", "exec", "-i", "-e", "MYSQL_PWD",
         os.getenv("COMMERCE_MYSQL_CONTAINER", "dev-infra-mysql84-1"),
         "mysql", "-u" + config["COMMERCE_DB_USER"], "commerce_local"],
        input=sql, text=True, capture_output=True, timeout=15,
        env=dict(os.environ, MYSQL_PWD=config["COMMERCE_DB_PASSWORD"]),
    )
    if result.returncode:
        raise SystemExit("本地运营凭据签发失败，请检查项目 MySQL 配置。")
    api.access["operatorToken"] = token
    who = api.get("/me", "operatorToken")
    if (who["tenantId"], who["actorId"], who["role"]) != ("demo", OPERATOR, "OPERATOR"):
        raise SystemExit("运营身份与本机演示租户不匹配。")


def seed_member_and_catalog(api, seed_at):
    post = api.post
    effective = seed_at.isoformat()
    for member_id, display_name, level in (
        (GOLD_MEMBER, "林晨 · 咖啡常客", "BASIC"),
        ("menu-new-member", "许宁 · 新入会会员", "BASIC"),
        ("menu-vip-member", "陈禾 · 品牌会员", "VIP"),
    ):
        post("/admin/members", member_id, {
            "memberId": member_id, "actorId": member_id, "displayName": display_name,
            "memberLevel": level,
        })
    for tag_id, name in ((TAG, "咖啡爱好者"), ("menu-repeat", "复购会员"),
                         ("menu-new", "新入会")):
        post("/admin/member-tags", tag_id, {"tagId": tag_id, "name": name})
    for member_id, tag_id in ((MEMBER, TAG), (GOLD_MEMBER, TAG), (GOLD_MEMBER, "menu-repeat"),
                              ("menu-new-member", "menu-new")):
        post(f"/admin/member-tags/{member_id}/assign", f"{member_id}-{tag_id}", {
            "tagId": tag_id, "active": True, "expectedVersion": 0, "reason": REASON,
        })
    post("/admin/member-growth/policies", "growth-policy", {
        "version": 1, "effectiveFrom": effective, "growthPerYuan": "1.00", "levels": LEVELS,
    })
    post("/admin/member-cycles/policies", "cycle-policy", {
        "version": 1, "effectiveFrom": effective, "periodDays": 30, "levels": LEVELS,
    })
    post(f"/admin/member-growth/{GOLD_MEMBER}/adjust", "growth-credit", {
        "expectedVersion": 0, "delta": 150, "reason": REASON,
    })
    post("/admin/member-points/policies", "points-policy", {
        "version": 1, "effectiveFrom": effective, "earnPerYuan": "1.00", "expiryDays": 180,
        "spendEnabled": True, "pointsPerYuan": 100, "maxDeductionBps": 5000,
    })
    for member_id, points in ((MEMBER, 1200), (GOLD_MEMBER, 800)):
        post(f"/admin/member-points/{member_id}/adjust", f"points-{member_id}", {
            "expectedVersion": 0, "delta": points, "reason": REASON,
        })
    post(f"/admin/member-behavior/{MEMBER}/profile", "birthday", {
        "expectedVersion": 0, "birthday": seed_at.strftime("%m-%d"),
        "journeyEnabled": True, "reason": REASON,
    })

    post("/admin/merchants", MERCHANT, {"merchantId": MERCHANT, "name": "河畔咖啡商贸"})
    # 开店走平台已有的类目，操作记录由开店流程产生。
    if not any(row["storeId"] == OPENING for row in api.get("/admin/stores")):
        post("/admin/store-openings", OPENING, {
            "storeId": OPENING, "merchantId": MERCHANT, "name": "河畔咖啡体验店",
            "subjectType": "ENTERPRISE", "categoryCode": "FOOD_DRINKS",
        })
    post("/admin/store-grants", "catalog-grant", {
        "grantId": "menu-catalog-grant", "actorId": OPERATOR, "resourceType": "STORE",
        "resourceId": STORE, "permission": "CATALOG", "reason": REASON,
    })
    store_query = "?" + urlencode({"storeId": STORE})
    category = "menu-coffee-category"
    if not any(row["categoryId"] == category
               for row in api.get("/operations/catalog-categories" + store_query)):
        post("/operations/catalog-categories", category, {
            "categoryId": category, "storeId": STORE, "name": "手冲咖啡",
        })
    post("/operations/specification-templates", "specification-template", {
        "templateId": "menu-coffee-pack", "version": 1, "storeId": STORE,
        "name": "手冲咖啡包装", "fields": [{"name": "规格", "values": ["10 袋", "20 袋"]}],
    })
    post("/operations/products", PRODUCT, {
        "productId": PRODUCT, "storeId": STORE, "title": "河畔挂耳咖啡", "category": "咖啡",
        "brand": "河畔咖啡",
    })
    post("/operations/products/" + PRODUCT + "/merchandising", "merchandising", {
        "storeId": STORE, "expectedVersion": 0, "categoryId": category,
        "description": "中度烘焙挂耳咖啡，独立小袋包装。",
        "images": [{"url": "/media/coffee.svg", "alt": "挂耳咖啡包装示意"}], "reason": REASON,
    })
    post("/operations/skus", SKU, {
        "skuId": SKU, "productId": PRODUCT, "storeId": STORE, "title": "河畔挂耳咖啡 10 袋",
        "unitPrice": "89.00", "specifications": [{"name": "规格", "value": "10 袋"}],
    })
    post("/operations/skus/" + SKU, "activate-sku", {
        "storeId": STORE, "expectedVersion": 1, "title": "河畔挂耳咖啡 10 袋",
        "unitPrice": "89.00", "status": "ACTIVE", "reason": REASON,
    })
    post("/admin/inventory/receipts", "inventory", {"storeId": STORE, "skuId": SKU, "quantity": 40})
    post("/operations/catalog-jobs", JOB, {
        "jobId": JOB, "storeId": STORE, "name": "挂耳咖啡批量调价演示", "action": "PRICE",
        "deadline": (seed_at + timedelta(hours=2)).isoformat(),
        "targets": [{"skuId": SKU, "expectedRevision": 2, "unitPrice": "85.00"}],
        "reason": REASON,
    })
    post("/operations/catalog-jobs/pump" + store_query, None)
    post(f"/operations/skus/{SKU}/channel-prices", "channel-price", {
        "storeId": STORE, "channel": "MINI_APP", "expectedVersion": 0, "unitPrice": "79.00",
        "validFrom": effective, "validTo": (seed_at + timedelta(days=90)).isoformat(),
        "active": True, "reason": REASON,
    })
    for kind in ("BROWSE", "ADD_TO_CART"):
        post("/members/me/behavior/events", "behavior-" + kind, {
            "eventId": "menu-coffee-" + kind.lower(), "kind": kind, "storeId": STORE, "skuId": SKU,
        }, "memberToken")


def seed_marketing(api, seed_at):
    post = api.post
    validity = {
        "validFrom": (seed_at - timedelta(minutes=1)).isoformat(),
        "validTo": (seed_at + timedelta(days=90)).isoformat(),
    }
    rule = {"kind": "COMPARE", "field": "memberTags", "operator": "CONTAINS",
            "valueType": "TEXT", "value": TAG}
    post("/admin/rules", RULE, {"ruleId": RULE, "version": 1, "name": "咖啡偏好规则", "rule": rule})
    post(f"/admin/rules/{RULE}/1/publish", "publish-rule")
    post("/admin/segments", SEGMENT, {
        "segmentId": SEGMENT, "version": 1, "name": "咖啡偏好会员", "rule": rule,
        "ttlSeconds": 3600, "refreshSeconds": 600, "maxMembers": 100,
    })
    post(f"/admin/segments/{SEGMENT}/refresh", "segment-refresh")
    post("/admin/segments/pump", None)
    post("/admin/audiences", AUDIENCE, {
        "audienceId": AUDIENCE, "version": 1, "name": "咖啡礼遇定向人群", "source": "LOCAL_DEMO",
        "watermark": seed_at.isoformat(), "validUntil": (seed_at + timedelta(hours=2)).isoformat(),
        "memberIds": [MEMBER, GOLD_MEMBER],
    })
    post("/admin/entitlement-definitions", BENEFIT, {
        "benefitId": BENEFIT, "version": 1, "storeId": STORE, "name": "咖啡试饮礼遇",
        "units": 2, "quota": 100, "validityDays": 30, **validity,
    })
    post("/admin/member-cycle-benefits", "cycle-benefit", {
        "bindingId": "menu-gold-cycle", "policyVersion": 1, "level": "GOLD", "storeId": STORE,
        "validUntil": validity["validTo"], "benefits": [{"benefitId": BENEFIT, "version": 1}],
    })
    post(f"/admin/member-cycle-benefits/{GOLD_MEMBER}/grant", "grant-cycle-benefit")
    post("/admin/campaigns", CAMPAIGN, {
        "campaignId": CAMPAIGN, "version": 1, "storeId": STORE, "name": "咖啡会员礼遇周",
        "minimumSpend": "60.00", "discountAmount": "10.00", **validity,
        "policy": {
            "rule": {"id": RULE, "version": 1},
            "terms": {
                "percentageBps": 0, "platformFundingBps": 5000, "budget": "1000.00",
                "pricing": {"includedSkuIds": [SKU], "excludedSkuIds": [], "tiers": []},
                "grant": {"benefitId": BENEFIT, "version": 1},
            },
        },
    })
    api.publish("campaigns", CAMPAIGN)
    post("/admin/coupon-definitions", COUPON, {
        "definitionId": COUPON, "version": 1, "storeId": STORE, "name": "咖啡关怀五元券",
        "minimumSpend": "0.00", "discountAmount": "5.00", "quota": 100, "stackable": True,
        "platformFundingBps": 10000, "issuanceMode": "SOURCE_ONLY", "validityDays": 30, **validity,
    })
    post("/admin/coupon-deliveries", DELIVERY, {
        "batchId": DELIVERY, "storeId": STORE, "name": "咖啡常客关怀发券",
        "definitionId": COUPON, "definitionVersion": 1, "audience": {"id": AUDIENCE, "version": 1},
        "deadline": (seed_at + timedelta(hours=1)).isoformat(), "minIntervalHours": 24,
    })
    post("/admin/coupon-deliveries/pump", None)
    post("/admin/point-offers", OFFER, {
        "offerId": OFFER, "storeId": STORE, "name": "积分兑换咖啡礼遇", "kind": "ENTITLEMENT",
        "assetId": BENEFIT, "assetVersion": 1, "points": 200, "quota": 50, "perMemberLimit": 2, **validity,
    })
    post(f"/point-offers/{OFFER}/redeem", "redeem-coffee", {}, "memberToken")
    post("/admin/journeys", JOURNEY, {
        "journeyId": JOURNEY, "version": 1, "storeId": STORE, "name": "会员生日礼遇",
        "trigger": "BIRTHDAY", **validity, "maxDurationSeconds": 3600, "entry": "notice",
        "controls": {"maxEntries": 1, "entryWindowSeconds": 86400,
                     "notificationLimit": 1, "notificationWindowSeconds": 86400},
        "lifecycle": {"thresholdDays": 7, "cartDelaySeconds": 60, "scanIntervalSeconds": 300,
                      "conversionWindowDays": 7},
        "nodes": [{"id": "notice", "kind": "NOTIFY", "next": "end",
                   "title": "生日快乐", "body": "欢迎查看您的咖啡会员礼遇。"},
                  {"id": "end", "kind": "END"}],
    })
    api.publish("journeys", JOURNEY)
    for _ in range(3):
        post("/admin/journeys/pump", None)


    post("/admin/ops-pages", OPS_PAGE, {
        "pageId": OPS_PAGE, "version": 1, "title": "咖啡会员运营台", "storeId": STORE,
        "sections": [{"id": "campaigns", "title": "咖啡活动", "source": "CAMPAIGNS"},
                     {"id": "budgets", "title": "活动预算", "source": "BUDGETS"},
                     {"id": "benefits", "title": "咖啡权益", "source": "ENTITLEMENTS"}],
        "actions": [{"id": "create-coupon", "label": "新建优惠券", "kind": "CREATE_COUPON"}],
    })
    api.publish("ops-pages", OPS_PAGE)


def seed_trade_and_runtime(api, seed_at):
    post = api.post

    def order(case):
        quote = post("/quotes", case + "-quote", {
            "storeId": STORE, "items": [{"skuId": SKU, "quantity": 1}],
        }, "memberToken")
        return post("/orders", case + "-order", {
            "quoteId": quote["quoteId"],
            "address": {"recipient": "演示会员", "phone": "13800000000", "detail": "本地沙箱地址"},
        }, "memberToken")

    def paid_order(case):
        paid = order(case)
        order_id = paid["orderId"]
        payment = post(f"/orders/{order_id}/payments", case + "-payment", identity="memberToken")
        post(f"/admin/sandbox/payments/{payment['paymentId']}/fact", case + "-fact", {"status": "PAID"})
        post(f"/admin/orders/{order_id}/payment/reconcile", None)
        # 履约记录出现后，售后才能引用已经付款的订单。
        pump_until(api, lambda: any(item["orderId"] == order_id for item in api.get(
            "/admin/fulfillments?" + urlencode({"orderId": order_id}))))
        return paid

    def aftersale(paid, case, reason):
        return post("/aftersales", case, {
            "orderId": paid["orderId"], "reason": reason,
            "items": [{"skuId": SKU, "quantity": 1}],
        }, "memberToken")

    pending = order("pending")
    paid = paid_order("paid")
    aftersale(paid, "pending-review", "演示售后，申请退回未拆封商品")
    refunded = paid_order("refunded")
    case = aftersale(refunded, "refund-case", "演示本地沙箱全额退款")
    approved = post(f"/admin/aftersales/{case['caseId']}/approve", "refund-approve")
    refund_id = approved["refundId"]
    post(f"/admin/sandbox/refunds/{refund_id}/success", "refund-success")
    post(f"/admin/refunds/{refund_id}/reconcile", None)
    pump_until(api, lambda: api.get(f"/aftersales/{case['caseId']}", "memberToken")["status"] == "COMPLETED")
    post("/admin/marketing-effects/rebuild", "effects-projection", {"after": "", "limit": 100})
    # 退款会按当前策略重新评估成长。用真实账本记录维持基础演示会员原有的 VIP 等级。
    wallet_path = f"/admin/member-growth/{MEMBER}"
    wallet = api.get(wallet_path)
    vip_threshold = LEVELS[-1]["minimumGrowth"]
    if wallet["growth"] < vip_threshold and not any(
        entry["reason"] == DEMO_GROWTH_REASON for entry in api.get(wallet_path + "/ledger")
    ):
        post(wallet_path + "/adjust", "demo-vip-growth", {
            "expectedVersion": wallet["version"], "delta": vip_threshold - wallet["growth"],
            "reason": DEMO_GROWTH_REASON,
        })

    # 正常订单未停止，真实命令会留下 NOT_STOPPED 审计，用来展示恢复保护。
    post("/admin/runtime/recoveries", "recovery-example", {
        "workType": "order.expiry", "action": "RETRY", "workIds": [pending["orderId"]],
        "reason": "演示恢复前置检查",
    })
    post("/admin/runtime/replays", REPLAY, {
        "jobId": REPLAY, "consumer": "marketing-effects-v1", "eventTypes": ["order.created.v1"],
        "from": seed_at.isoformat(), "to": paid["createdAt"], "mode": "UNPROCESSED",
        "maxEvents": 20, "reason": "演示仅重放未处理的订单投影",
    })
    replay = api.get(f"/admin/runtime/replays/{REPLAY}")
    if replay["status"] == "RUNNING":
        post(f"/admin/runtime/replays/{REPLAY}/control", "pause-replay", {
            "action": "PAUSE", "expectedVersion": replay["version"], "reason": "演示暂停的重放任务",
        })


def pump_until(api, ready):
    # 本地后台工作器可能关闭。限制推进轮数，避免在部分失败时无限重试。
    for _ in range(8):
        api.post("/admin/events/pump", None)
        if ready():
            return
    raise SystemExit("演示订单的事件尚未推进到预期状态，可重跑相同命令继续。")


def seed_platform(api):
    identity = "platformToken"
    who = api.get("/platform/me", identity)
    if (who["tenantId"], who["role"]) != ("platform", "PLATFORM_OPERATOR"):
        raise SystemExit("请使用本地平台类目维护专岗凭据。")
    path = "/platform/commerce-categories"
    directory = api.get(path, identity)
    if not any(row["code"] == PLATFORM_CATEGORY for row in directory["items"]):
        if not directory["canEdit"]:
            raise SystemExit("平台分类演示需要类目维护专岗。")
        api.post(path, "platform-coffee-category", {
            "code": PLATFORM_CATEGORY, "parentCode": "FOOD", "name": "咖啡与可可（演示）",
            "kind": "LEAF", "sortOrder": 50, "reason": REASON,
        }, identity)



def expect(api, path, expected, identity="adminToken", envelope=None):
    data = api.get(path, identity)
    if envelope:
        data = data[envelope]
    rows = data if isinstance(data, list) else [data]
    for row in rows:
        content = row.get("content") or row.get("definition") or {}
        if all(row.get(key, content.get(key)) == value for key, value in expected.items()):
            print(f"PASS {identity} {path} ({len(rows)})")
            return row
    raise SystemExit(f"验收失败 {identity} {path}，缺少预期记录 {expected}")


def verify(api, seed_at):
    store_query = "?" + urlencode({"storeId": STORE})
    for path, field, value in (
        ("/admin/members", "memberId", GOLD_MEMBER),
        ("/admin/member-growth/policies", "version", 1),
        (f"/admin/member-growth/{GOLD_MEMBER}", "growth", 150),
        ("/admin/member-tags", "tagId", TAG),
        ("/admin/segments", "segmentId", SEGMENT),
        (f"/admin/segments/{SEGMENT}/runs", "status", "COMPLETED"),
        ("/admin/audiences", "audienceId", AUDIENCE),
        ("/admin/merchants", "merchantId", MERCHANT),
        ("/admin/stores", "storeId", OPENING),
        (f"/admin/store-openings/{OPENING}", "categoryCode", "FOOD_DRINKS"),
        ("/admin/store-grants", "grantId", "menu-catalog-grant"),
        ("/operations/products" + store_query, "productId", PRODUCT),
        ("/operations/skus" + store_query, "skuId", SKU),
        ("/operations/catalog-categories" + store_query, "categoryId", "menu-coffee-category"),
        ("/operations/specification-templates" + store_query, "templateId", "menu-coffee-pack"),
        ("/operations/catalog-jobs" + store_query, "jobId", JOB),
        (f"/operations/skus/{SKU}/channel-prices" + store_query, "channel", "MINI_APP"),
        ("/admin/inventory" + store_query, "skuId", SKU),
        ("/admin/campaigns", "campaignId", CAMPAIGN),
        ("/admin/campaign-budgets", "campaignId", CAMPAIGN),
        ("/admin/rules", "ruleId", RULE),
        ("/admin/coupon-definitions" + store_query, "definitionId", COUPON),
        ("/admin/coupon-deliveries" + store_query, "batchId", DELIVERY),
        ("/admin/entitlement-definitions" + store_query, "benefitId", BENEFIT),
        ("/admin/entitlements", "benefitId", BENEFIT),
        ("/admin/journeys", "journeyId", JOURNEY),
        ("/admin/journey-instances", "journeyId", JOURNEY),
        ("/admin/journey-scans", "journeyId", JOURNEY),
        ("/admin/ops-pages", "pageId", OPS_PAGE),
        ("/admin/runtime/replays", "jobId", REPLAY),
        ("/admin/runtime/recoveries", "reason", "演示恢复前置检查"),
        ("/admin/member-points/policies", "version", 1),
        (f"/admin/member-cycles/{GOLD_MEMBER}", "enabled", True),
        ("/admin/member-cycle-benefits?policyVersion=1", "bindingId", "menu-gold-cycle"),
        ("/admin/point-offers" + store_query, "offerId", OFFER),
    ):
        expect(api, path, {field: value})

    expect(api, "/admin/dashboard" + store_query, {"storeId": STORE})
    expect(api, f"/admin/store-openings/{OPENING}/operation-audits",
           {"action": "store.opening.draft", "result": "SUCCEEDED"}, envelope="items")
    expect(api, f"/admin/coupon-deliveries/{DELIVERY}/recipients",
           {"memberId": MEMBER, "status": "ISSUED"})
    report_query = "?" + urlencode({
        "storeId": STORE,
        "from": (seed_at - timedelta(days=1)).isoformat(),
        "to": min(datetime.now(timezone.utc), seed_at + timedelta(days=7)).isoformat(),
    })
    expect(api, "/admin/marketing-effects" + report_query, {"campaignId": CAMPAIGN}, envelope="rows")

    # 以演示活动关联订单、支付、履约、售后、退款和事件，防止把历史无关记录当成验收。
    executions = api.get("/admin/marketing-executions")
    order_ids = {row["orderId"] for row in executions if row["campaignId"] == CAMPAIGN}
    refund = next((row for row in api.get("/admin/refunds") if row["orderId"] in order_ids), None)
    if len(order_ids) < 3 or not refund or refund["status"] != "SUCCEEDED":
        raise SystemExit("演示营销执行、成交订单或全额退款缺失。")
    order_id = refund["orderId"]
    order_query = "?" + urlencode({"orderId": order_id})
    expect(api, "/admin/orders/" + order_id, {"orderId": order_id, "memberId": MEMBER})
    expect(api, f"/admin/orders/{order_id}/payment", {"status": "PAID"})
    expect(api, "/admin/fulfillments" + order_query, {"orderId": order_id})
    expect(api, "/admin/aftersales" + order_query, {"orderId": order_id, "status": "COMPLETED"})
    expect(api, "/admin/events?" + urlencode({"aggregateId": order_id}), {"aggregateId": order_id})
    print(f"PASS adminToken 营销执行与退款 ({len(order_ids)} 笔演示订单)")

    for path, field, value in (
        ("/catalog/search" + store_query, "skuId", SKU),
        ("/orders", "orderId", order_id),
        ("/coupons", "definitionId", COUPON),
        ("/entitlements", "benefitId", BENEFIT),
        ("/members/me/growth", "memberId", MEMBER),
        ("/members/me/growth/ledger", "reason", DEMO_GROWTH_REASON),
        ("/members/me/points/ledger", "action", "EXCHANGE"),
        ("/members/me/behavior/events", "eventId", "menu-coffee-browse"),
        ("/aftersales", "orderId", order_id),
        ("/notifications", "title", "生日快乐"),
    ):
        expect(api, path, {field: value}, "memberToken")

    if "operatorToken" in api.access:
        expect(api, "/operations/stores", {"storeId": STORE}, "operatorToken")
        expect(api, "/operations/products" + store_query, {"productId": PRODUCT}, "operatorToken")
    if "platformToken" in api.access:
        expect(api, "/platform/commerce-categories", {"code": PLATFORM_CATEGORY},
               "platformToken", "items")
        expect(api, "/platform/operation-audits?" + urlencode({"resourceId": PLATFORM_CATEGORY}),
               {"resourceId": PLATFORM_CATEGORY, "result": "SUCCEEDED"}, "platformToken", "items")
        summary = api.get("/platform/runtime", "platformToken")
        if not summary.get("observedAt") or not summary.get("lanes"):
            raise SystemExit("平台运行健康没有返回实际观测。")
        print("PASS platformToken /platform/runtime")
    else:
        print("SKIPPED platformToken：未提供本机平台类目专岗凭据")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8602")
    parser.add_argument("--access", type=Path, default=ROOT / ".local/demo-access.json")
    parser.add_argument("--verify", action="store_true", help="只核验现有业务数据")
    parser.add_argument("--platform-access", type=Path, help="使用本地类目专岗补齐平台类目与操作日志")
    args = parser.parse_args()
    base = urlsplit(args.base_url)
    if (base.scheme != "http" or base.hostname not in ("127.0.0.1", "localhost")
            or base.port is None or base.username or base.password or base.path not in ("", "/")
            or base.query or base.fragment):
        parser.error("仅支持带端口的本机回环 HTTP 入口")
    access = json.loads(args.access.read_text())
    if args.platform_access:
        access["platformToken"] = json.loads(args.platform_access.read_text())["platformToken"]
    api = DemoApi(args.base_url.rstrip("/"), access)
    api.validate_target()
    fixture_dir = args.access.parent / "commerce-menu-demo-data"
    clock_path = fixture_dir / "seed-clock"
    if args.verify:
        if not clock_path.exists():
            parser.error("请先运行导入，以建立演示数据时钟。")
        seed_at = datetime.fromisoformat(clock_path.read_text().strip())
        operator_path = fixture_dir / "operator-access.json"
        if operator_path.exists():
            access.update(json.loads(operator_path.read_text()))
    else:
        fixture_dir.mkdir(parents=True, exist_ok=True)
        with (fixture_dir / "seed.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SystemExit("该演示租户正在导入，请等本次导入完成。") from None
            seed_at = seed_clock(clock_path)
            provision_operator(api, fixture_dir)
            seed_member_and_catalog(api, seed_at)
            seed_marketing(api, seed_at)
            seed_trade_and_runtime(api, seed_at)
            if args.platform_access:
                seed_platform(api)
    verify(api, seed_at)


if __name__ == "__main__":
    main()
