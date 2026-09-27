#!/usr/bin/env python3
"""Exercise the real quote API against the isolated Phase 7 benchmark database.

Requires local tokens.json created for the three benchmark tenants. Each request
uses its own idempotency key and writes a quote to the benchmark database.
"""

import concurrent.futures
import json
import pathlib
import statistics
import time
import urllib.error
import urllib.request
import uuid

TOKENS = json.loads(pathlib.Path(".local/phase7-bench/tokens.json").read_text())
URL = "http://127.0.0.1:8623/v1/quotes"
BODY = json.dumps({"storeId": "store1", "items": [{"skuId": "sku1", "quantity": 1}]}).encode()


def quote(token):
    request = urllib.request.Request(
        URL, data=BODY, method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Idempotency-Key": str(uuid.uuid4())})
    started = time.perf_counter_ns()
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = json.loads(response.read())
            return response.status, (time.perf_counter_ns() - started) / 1e6, len(body.get("trace", []))
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, (time.perf_counter_ns() - started) / 1e6, 0


def percentile(values, percentage):
    return values[min(len(values) - 1, round((len(values) - 1) * percentage))]


print("tier,concurrency,samples,success,trace_candidates,p50_ms,p95_ms,p99_ms,throughput_per_second")
for tier in ("small", "medium", "large"):
    token = TOKENS[tier]["member"]
    for _ in range(10):
        status, _, _ = quote(token)
        if status != 200:
            raise RuntimeError(f"warmup {tier}: HTTP {status}")
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        samples = list(pool.map(quote, [token] * 120))
    elapsed = time.perf_counter() - started
    durations = sorted(sample[1] for sample in samples)
    success = sum(sample[0] == 200 for sample in samples)
    traces = statistics.mode(sample[2] for sample in samples if sample[0] == 200)
    print(f"{tier},8,120,{success},{traces},{percentile(durations,.50):.2f},"
          f"{percentile(durations,.95):.2f},{percentile(durations,.99):.2f},{120 / elapsed:.1f}")
    if success != 120:
        raise RuntimeError(f"{tier}: {120-success} quote requests failed")
