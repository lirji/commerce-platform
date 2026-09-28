"""Owned Phase 8 process probes. Only commerce_phase8_bench is written.

Load .local/runtime.env before invoking scripts. Tokens and app logs stay ignored;
no credentials are printed. Processes launched here are the only processes stopped.
"""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import uuid
from http import HTTPStatus
from urllib.error import HTTPError
from urllib.request import Request, urlopen

DB = os.environ.get('COMMERCE_PHASE8_PROBE_DB', 'commerce_phase8_bench')
assert DB in ('commerce_phase8_bench', 'commerce_phase8_recovery')
ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / '.local/phase8-process'
OUT.mkdir(parents=True, exist_ok=True)
RUN = uuid.uuid4().hex[:8]
PROCESSES = []
TOKENS = {}


def sql(statement, database=DB):
    assert database in (DB, 'mysql')
    result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-mysql84-1', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ' + database + ' -N -r'],
        input=("SET time_zone='+00:00';\n" + statement).encode(), capture_output=True)
    if result.returncode:
        # SQL text and driver output may include credentials. Keep them out of tool output.
        raise RuntimeError('Owned benchmark SQL failed (details suppressed)')
    return result.stdout.decode().strip()


def prepare_database():
    user = os.environ['COMMERCE_DB_USER']
    assert re.fullmatch(r'[A-Za-z0-9_]+', user)
    statement = f'CREATE DATABASE IF NOT EXISTS {DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;'
    if user != 'root':
        statement += f"GRANT ALL ON {DB}.* TO '{user}'@'%';"
    sql(statement, 'mysql')


def call(port, path, tenant=None, body=None, method='POST', role='admin'):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
    if tenant:
        headers['Authorization'] = 'Bearer ' + TOKENS[tenant][role]
    request = Request(f'http://127.0.0.1:{port}' + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(request, timeout=40) as reply:
            content = reply.read()
            return reply.status, json.loads(content) if content else None
    except HTTPError as failure:
        return failure.code, json.loads(failure.read())


def post(port, path, tenant, body=None):
    status, result = call(port, path, tenant, body)
    assert status == HTTPStatus.OK, (path, status, result)
    return result


def launch(port, workers=False, jar=None):
    original = os.environ['COMMERCE_TEST_DB_URL']
    assert '/commerce_test_20260923?' in original
    environment = dict(os.environ, COMMERCE_DB_URL=original.replace('/commerce_test_20260923?', '/' + DB + '?'),
        COMMERCE_PORT=str(port), COMMERCE_WORKERS_ENABLED=str(workers).lower(), COMMERCE_SANDBOX_ENABLED='true')
    log_path = OUT / f'{RUN}-{port}-{time.time_ns()}.log'
    with log_path.open('w') as log:
        process = subprocess.Popen(['java', '-jar', str(jar or Path(os.environ.get('COMMERCE_PHASE8_PROBE_JAR', str(ROOT / 'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar'))))],
            cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    PROCESSES.append((process, log_path))
    last_error = 'not-ready'
    until = time.monotonic() + 90
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError('Owned probe application failed to start; inspect private redacted log')
        try:
            if call(port, '/actuator/health', method='GET')[0] == HTTPStatus.OK:
                return process
        except OSError as unavailable:
            last_error = type(unavailable).__name__
        time.sleep(0.3)
    raise RuntimeError('Owned probe application readiness timeout: ' + last_error)


def stop(process, force=False):
    if process.poll() is None:
        process.kill() if force else process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def cleanup():
    for process, path in PROCESSES:
        stop(process)
        if path.exists():
            path.write_text(re.sub(r'Using generated security password: [^\n]+',
                'Using generated security password: [redacted]', path.read_text()))


def at(seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def fixture(port, suffix, credit=True):
    tenant = 'p8-' + RUN + '-' + suffix
    access = {role: secrets.token_hex(32) for role in ('admin', 'member', 'platform')}
    TOKENS[tenant] = access
    for role, value in access.items():
        actor, actual = ('buyer', 'MEMBER') if role == 'member' else ('platform', 'PLATFORM_OPERATOR') if role == 'platform' else ('admin', 'ADMIN')
        sql("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES "
            f"('{hashlib.sha256(value.encode()).hexdigest()}','{tenant}','{actor}','{actual}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY));")
    post(port, '/v1/admin/members', tenant, dict(memberId='m1', actorId='buyer', displayName='阶段8验证会员', memberLevel='VIP'))
    post(port, '/v1/admin/merchants', tenant, dict(merchantId='merchant1', name='阶段8验证商家'))
    post(port, '/v1/admin/stores', tenant, dict(storeId='store1', merchantId='merchant1', name='阶段8验证店铺'))
    post(port, '/v1/admin/skus', tenant, dict(skuId='sku1', storeId='store1', title='阶段8商品', unitPrice='25.00'))
    if credit:
        post(port, '/v1/admin/entitlement-definitions', tenant, dict(benefitId='credit', version=1, storeId='store1',
            name='阶段8信用权益', units=2, quota=20000, validFrom=at(-60), validTo=at(172800), validityDays=1))
    return tenant


def publish(port, tenant, nodes, version=1, trigger='MANUAL', name='probe'):
    definition = dict(journeyId=name, version=version, storeId='store1', name='阶段8恢复验证', trigger=trigger,
        validFrom=at(-30), validTo=at(86400), maxDurationSeconds=3600, entry=nodes[0]['id'], nodes=nodes)
    post(port, '/v1/admin/journeys', tenant, definition)
    for expected, action in enumerate(('submit', 'approve', 'publish')):
        post(port, f'/v1/admin/journeys/{name}/{version}/{action}', tenant, dict(expectedVersion=expected))
    return definition


def enroll(port, tenant, event='one', version=1, name='probe'):
    return post(port, '/v1/admin/journey-instances', tenant,
        dict(journeyId=name, version=version, memberId='m1', eventKey=event))['instanceId']


def history(port, tenant, instance):
    status, result = call(port, f'/v1/admin/journey-instances/{instance}/history', tenant, method='GET')
    assert status == HTTPStatus.OK
    return result


def pump(port, tenant):
    return post(port, '/v1/admin/journeys/pump', tenant)


def wait_until(predicate, seconds=60):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.2)
    raise AssertionError('Owned probe timed out')
