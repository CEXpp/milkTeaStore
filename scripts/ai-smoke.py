# -*- coding: utf-8 -*-
"""AI 点单升级验收脚本：流式链路 + 口味记忆闭环。

覆盖本轮升级的后端验收项：
    AI-01 非流式对话仍可用（降级出口未被破坏）
    AI-02 草稿转订单 → 支付闭环
    AI-03 口味偏好由已成交订单**隐式聚合**（订单事件驱动，无需人工触发）
    AI-04 流式接口推送 stage / delta / decision / done 事件并以空行正确分帧
    AI-05 流式失败也走 error 事件（带业务码），不是 HTTP 500

用法：
    python scripts/ai-smoke.py [host:port]        # 默认 127.0.0.1:8080
    bash   scripts/ai-smoke.sh [host:port]        # 入口包装（Git Bash / Unix）

环境变量：
    AI_SMOKE_OPENID       冒烟顾客 openid 前缀（默认 smoke-ai，运行时会追加时间戳，
                          保证「新客无偏好」这条断言每次运行都成立）
    AI_SMOKE_MESSAGE      首轮点单话术（默认「来一杯珍珠奶茶，大杯半糖」）
    AI_SMOKE_SKIP_AI      置 1 时跳过需要真实模型的用例（只验接口连通与降级口径）
    SMOKE_LOG_FILE        可选：后端日志文件路径；设置后校验期间无 ERROR 行

退出码：0 = 全部通过（输出 AI_GATE_PASS）；1 = 存在失败（输出 AI_GATE_FAIL）。

前置条件（缺一即会失败，属预期）：
    1. 后端已启动且可访问（docker compose up -d 或 mvnw spring-boot:run）；
    2. `backend/.env` 已注入 AI_API_KEY（GLM），否则非流式对话会返回 1008；
    3. 数据库已执行到 Flyway V9（customer_preference 表与 customer.avatar_key 列存在）。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://' + (sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1:8080')
OPENID = '%s-%d' % (os.environ.get('AI_SMOKE_OPENID', 'smoke-ai'), int(time.time()))
MESSAGE = os.environ.get('AI_SMOKE_MESSAGE', '来一杯珍珠奶茶，大杯半糖')
SKIP_AI = os.environ.get('AI_SMOKE_SKIP_AI', '') == '1'
LOG_FILE = os.environ.get('SMOKE_LOG_FILE', '')

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
passed = 0
failed = []
skipped = []


def api(method, path, token=None, body=None, timeout=30):
    """调用后端接口，返回 (http_status, 统一响应体 dict)。"""
    url = BASE + path
    data = None
    headers = {'Content-Type': 'application/json'}
    if body is not None:
        data = json.dumps(body).encode('utf-8')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {}


def step(name, cond, extra=''):
    global passed
    if cond:
        passed += 1
        print('PASS %s %s' % (name, extra))
    else:
        failed.append(name)
        print('FAIL %s %s' % (name, extra))


def skip(name, reason):
    skipped.append(name)
    print('SKIP %s %s' % (name, reason))


def stream(path, token, body, deadline_seconds):
    """POST 并逐块读取响应，返回 (status_code, 累积文本, 抛出异常)。"""
    url = BASE + path
    data = json.dumps(body).encode('utf-8')
    req = urllib.request.Request(url, data=data, method='POST', headers={
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': 'Bearer ' + token,
    })
    chunks = []
    status = 0
    error = None
    deadline = time.time() + deadline_seconds
    try:
        with OPENER.open(req, timeout=deadline_seconds) as resp:
            status = resp.status
            while time.time() < deadline:
                block = resp.read(1)
                if not block:
                    break
                chunks.append(block)
                text = b''.join(chunks).decode('utf-8', errors='replace')
                if 'event:done' in text or 'event:error' in text:
                    break
    except Exception as e:  # noqa: BLE001 —— 网络异常本身就是一条待断言的结果
        error = e
    return status, b''.join(chunks).decode('utf-8', errors='replace'), error


def event_names(raw):
    """从原始事件流里取出所有 event 名（按出现顺序）。"""
    names = []
    for line in raw.split('\n'):
        if line.startswith('event:'):
            names.append(line[len('event:'):].strip())
    return names


def event_data(raw, name):
    """取某类事件的 data 原文列表。"""
    datas = []
    current = None
    buffer = []
    for line in raw.split('\n'):
        if line.startswith('event:'):
            if current == name and buffer:
                datas.append(''.join(buffer))
            current = line[len('event:'):].strip()
            buffer = []
        elif line.startswith('data:') and current is not None:
            buffer.append(line[len('data:'):].strip())
    if current == name and buffer:
        datas.append(''.join(buffer))
    return datas


print('=== AI 点单升级验收 @ %s ===' % BASE)
print('OPENID=%s' % OPENID)

# ---------- 1. 顾客登录 ----------
st, r = api('GET', '/api/customer/dev-login?openid=' + OPENID)
ok = r.get('code') == 0 and r.get('data', {}).get('token')
step('[1] customer dev-login', ok, 'openid=' + OPENID)
if not ok:
    print('AI_GATE_FAIL')
    sys.exit(1)
customer = r['data']['token']

# ---------- 2. 新客无偏好：不注入任何默认值（口径：宁可问一句，不可猜错） ----------
st, r = api('GET', '/api/customer/preference', customer)
d = r.get('data') or {}
ok = (r.get('code') == 0 and d.get('available') is False
      and int(d.get('sampleOrders') or 0) == 0 and not d.get('groups'))
step('[2] fresh customer has no preference (AI-03)', ok, json.dumps(d, ensure_ascii=False))

# ---------- 3. 菜单可读（AI 决策上下文的数据源） ----------
st, r = api('GET', '/api/customer/menu')
products = [p for c in (r.get('data') or {}).get('categories', []) for p in c.get('products', [])]
step('[3] menu readable', r.get('code') == 0 and len(products) >= 1, 'products=%d' % len(products))

# ---------- 4. 非流式对话（降级出口：升级后必须仍然可用） ----------
session_id = None
if SKIP_AI:
    skip('[4] non-stream chat', 'AI_SMOKE_SKIP_AI=1')
else:
    st, r = api('POST', '/api/customer/ai/chat', customer, {'message': MESSAGE})
    d = r.get('data') or {}
    session_id = d.get('sessionId')
    ok = r.get('code') == 0 and bool(session_id) and d.get('replyType') in ('TEXT', 'CARD') and bool(d.get('text'))
    step('[4] non-stream chat returns TEXT/CARD (AI-01)', ok,
         'code=%s replyType=%s' % (r.get('code'), d.get('replyType')))
    if r.get('code') == 1008:
        print('HINT 返回 1008：后端未配置 AI_API_KEY（GLM），请先注入 backend/.env')

# ---------- 5~6. 草稿转订单 → 支付（口味记忆的数据来源） ----------
order_id = None
if session_id:
    st, r = api('POST', '/api/customer/ai/confirm-order', customer, {'sessionId': session_id})
    d = r.get('data') or {}
    order_id = d.get('orderId')
    step('[5] confirm-order => orderId (AI-02)', r.get('code') == 0 and bool(order_id),
         'code=%s orderId=%s' % (r.get('code'), order_id))
    if order_id:
        st, r = api('POST', '/api/customer/orders/%d/pay' % order_id, customer, {})
        d = r.get('data') or {}
        step('[6] pay => PAID + pickupCode (AI-02)',
             r.get('code') == 0 and d.get('status') == 'PAID' and bool(d.get('pickupCode')),
             'status=%s pickupCode=%s' % (d.get('status'), d.get('pickupCode')))
else:
    skip('[5] confirm-order', 'no draft from chat')
    skip('[6] pay', 'no order')

# ---------- 7. 口味记忆闭环：由订单事件异步聚合，无需人工触发 ----------
if order_id:
    seen = 0
    deadline = time.time() + 20
    last = {}
    while time.time() < deadline:
        st, r = api('GET', '/api/customer/preference', customer)
        last = r.get('data') or {}
        seen = int(last.get('sampleOrders') or 0)
        if seen >= 1:
            break
        time.sleep(2)
    step('[7] preference aggregated from paid order (AI-03)', seen >= 1,
         'sampleOrders=%s (available=%s)' % (seen, last.get('available')))
else:
    skip('[7] preference aggregated', 'no paid order')

# ---------- 8~10. 流式链路 ----------
if SKIP_AI:
    skip('[8] stream stage/delta/done', 'AI_SMOKE_SKIP_AI=1')
    skip('[9] stream framing', 'AI_SMOKE_SKIP_AI=1')
    skip('[10] stream error contract', 'AI_SMOKE_SKIP_AI=1')
else:
    st, raw, err = stream('/api/customer/ai/chat/stream', customer,
                          {'sessionId': session_id, 'message': MESSAGE},
                          deadline_seconds=40)
    names = event_names(raw)
    step('[8] stream returns SSE events (AI-04)', st == 200 and bool(names),
         'status=%s events=%s err=%s' % (st, names, err))

    has_done = 'done' in names
    has_progress = ('delta' in names) or ('error' in names) or ('decision' in names)
    step('[9] stream carries progress and a terminal event (AI-04)', has_done and has_progress,
         'events=%s' % names)

    # 分帧：每个事件块都应以空行结束（最后一块可能被提前 break 截断，故只看非末尾块）
    frames = raw.split('\n\n')
    well_formed = sum(1 for f in frames[:-1] if f.strip())
    step('[10] stream frames separated by blank line (AI-04)', well_formed >= 1,
         'frames=%d' % well_formed)

    # error 契约：若走了错误分支，必须带业务码（前端据此复用既有错误处理）
    errors = event_data(raw, 'error')
    if errors:
        try:
            payload = json.loads(errors[0])
            ok = isinstance(payload.get('code'), int) and payload.get('code') != 0
        except Exception:
            ok = False
        step('[11] error event carries business code (AI-05)', ok, errors[0][:120])
    else:
        skip('[11] error event contract', '本轮未触发错误分支')

    # done 必须回传 sessionId，前端据此延续上下文
    dones = event_data(raw, 'done')
    if dones:
        try:
            payload = json.loads(dones[0])
            ok = bool(payload.get('sessionId')) and payload.get('replyType') in ('TEXT', 'CARD')
        except Exception:
            ok = False
        step('[12] done event carries sessionId + replyType (AI-04)', ok, dones[0][:120])
    else:
        step('[12] done event carries sessionId + replyType (AI-04)', False, 'done 事件缺失')

# ---------- 13. 日志无 ERROR（可选） ----------
if LOG_FILE:
    try:
        with open(LOG_FILE, 'r', encoding='utf-8', errors='replace') as handle:
            errors = [line for line in handle if ' ERROR ' in line]
        step('[13] backend log has no ERROR', not errors, 'errorLines=%d' % len(errors))
    except OSError as e:
        step('[13] backend log readable', False, str(e))
else:
    skip('[13] backend log check', 'SMOKE_LOG_FILE 未设置')

print('---')
print('passed=%d failed=%d skipped=%d' % (passed, len(failed), len(skipped)))
if failed:
    print('AI_GATE_FAIL :: ' + ', '.join(failed))
    sys.exit(1)
print('AI_GATE_PASS')
sys.exit(0)
