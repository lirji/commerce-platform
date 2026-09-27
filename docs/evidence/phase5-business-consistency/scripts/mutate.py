#!/usr/bin/env python3
"""Phase 5 定向负测试；每个变异后恢复源码并校验哈希，备份留在 .local。"""

from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import time

root = Path(__file__).resolve().parents[4]
output = root / ".local" / "phase5-mutations"
output.mkdir(parents=True, exist_ok=True)

# 名称、模块、文件、原片段、变异片段、必须失败的测试。
mutations = [
    (
        "expiry-includes-paid",
        "order-runtime",
        "order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml",
        "<sql id=\"expiryDue\">status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS')",
        "<sql id=\"expiryDue\">status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS','PAID')",
        "PersistedCommerceTest#staleExpiryCannotCancelAnAlreadyPaidOrder",
    ),
    (
        "payment-without-durable-event",
        "payment",
        "payment/src/main/java/com/lrj/commerce/payment/charge/application/PaymentService.java",
        "if (updated.status() == Status.PAID || updated.status() == Status.CLOSED)\n",
        "if (false)\n",
        "PersistedCommerceTest#concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect",
    ),
    (
        "refund-without-cumulative-reservation",
        "payment",
        "payment/src/main/java/com/lrj/commerce/payment/refund/application/RefundService.java",
        "if (paymentId != null && payments.reserveRefund(tenant, paymentId, amount) != 1)",
        "if (false && paymentId != null && payments.reserveRefund(tenant, paymentId, amount) != 1)",
        "PersistedCommerceTest#concurrentRefundReservationsCannotExceedReceivedAmount",
    ),
]


def run(args, log):
    with log.open("a") as stream:
        return subprocess.run(args, cwd=root, stdout=stream, stderr=subprocess.STDOUT).returncode


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


bootstrap = output / "bootstrap.log"
if run(["mvn", "-B", "-o", "-q", "install", "-DskipTests"], bootstrap):
    raise SystemExit("Current reactor could not be installed for isolated mutation tests")

results = []
for name, module, relative, original, altered, test in mutations:
    source = root / relative
    backup = output / f"{name}.backup"
    log = output / f"{name}.log"
    before = digest(source)
    body = source.read_text()
    if body.count(original) != 1:
        raise SystemExit(f"{name}: mutation anchor is not unique")
    shutil.copy2(source, backup)
    try:
        source.write_text(body.replace(original, altered))
        os.utime(source, None)
        started = time.monotonic()
        compiled = run(["mvn", "-B", "-o", "-q", "install", "-DskipTests", "-pl", module], log) == 0
        code = (
            run(
                ["mvn", "-B", "-o", "test", "-pl", "commerce-app", f"-Dtest={test}"],
                log,
            )
            if compiled
            else -1
        )
        duration = round(time.monotonic() - started, 1)
    finally:
        shutil.move(backup, source)
        os.utime(source, None)
        restored = digest(source) == before
        reinstalled = run(["mvn", "-B", "-o", "-q", "install", "-DskipTests", "-pl", module], log) == 0
    summaries = [tuple(map(int, match)) for match in re.findall(
        r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", log.read_text()
    )]
    assertion_failed = any(total == 1 and failures + errors > 0 for total, failures, errors in summaries)
    result = {
        "mutation": name,
        "source": relative,
        "test": test,
        "compiled": compiled,
        "detected": compiled and code != 0 and assertion_failed,
        "restored": restored,
        "reinstalled": reinstalled,
        "seconds": duration,
    }
    results.append(result)
    print(json.dumps(result, ensure_ascii=False), flush=True)
    (output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    if not all((result["compiled"], result["detected"], restored, reinstalled)):
        raise SystemExit(f"{name}: mutation proof or restoration failed")
