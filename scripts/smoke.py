# -*- coding: utf-8 -*-
"""
M1 出口门禁冒烟脚本（T16）：一条脚本串起顾客端主链路，逐步断言关键字段。

    登录 → 菜单 → 下单 → 支付 → 状态查询 → 商家开始制作 → 状态变 PREPARING
    → 出餐 → 完成 → 超时关单验证（造超时单）

覆盖验收用例（后端侧）：
    AC-02 规格点单与价格计算 · AC-03 购物车结算（后端链路） ·
    AC-04 订单四态流转（后端侧） · AC-05 待支付超时自动关闭

用法：
    python scripts/smoke.py [host:port]        # 默认 127.0.0.1:8080
    bash   scripts/smoke.sh [host:port]        # 入口包装（Git Bash / Unix）

环境变量：
    SMOKE_OPENID          冒烟顾客 openid（默认 smoke-customer，避免污染联调数据）
    SMOKE_ADMIN_USER     商家账号（默认 admin）
    SMOKE_ADMIN_PASSWORD 商家密码（默认 admin123，对齐 application-dev.yml）
    SMOKE_LOG_FILE        可选：后端日志文件路径；设置后校验验收期间无 ERROR 行
    SMOKE_SKIP_TIMEOUT    =1 跳过超时关单验证（15 分钟生产配置下脚本不长时间等待）

退出码：0 = 全部通过（输出 M1_GATE_PASS）；1 = 存在失败（输出 M1_GATE_FAIL）。
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta

BASE = 'http://' + (sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1:8080')
OPENID = os.environ.get('SMOKE_OPENID', 'smoke-customer')
ADMIN_USER = os.environ.get('SMOKE_ADMIN_USER', 'admin')
ADMIN_PASSWORD = os.environ.get('SMOKE_ADMIN_PASSWORD', 'admin123')
LOG_FILE = os.environ.get('SMOKE_LOG_FILE', '')
SKIP_TIMEOUT = os.environ.get('SMOKE_SKIP_TIMEOUT', '') == '1'

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
passed = 0
failed = []
TIME_FMT = '%Y-%m-%d %H:%M:%S'


def api(method, path, token=None, body=None, timeout=15):
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


def is_amount(value):
    """金额契约：两位小数字符串，如 "12.00"。"""
    return isinstance(value, str) and re.fullmatch(r'\d+\.\d{2}', value) is not None


# ---------- 1. 顾客登录（AC 前置） ----------
st, r = api('GET', '/api/customer/dev-login?openid=' + OPENID)
ok = r.get('code') == 0 and r.get('data', {}).get('token')
step('[1] customer dev-login', ok, 'openid=' + OPENID)
if not ok:
    print('M1_GATE_FAIL')
    sys.exit(1)
customer = r['data']['token']

# ---------- 2. 菜单（AC-02 数据源） ----------
st, r = api('GET', '/api/customer/menu')
menu = r.get('data') or {}
categories = menu.get('categories') or []
products = [p for c in categories for p in c.get('products', [])]
ok = r.get('code') == 0 and len(categories) >= 1 and len(products) >= 1
step('[2] menu (AC-02)', ok, 'categories=%d products=%d' % (len(categories), len(products)))
if not ok:
    print('M1_GATE_FAIL')
    sys.exit(1)
product = products[0]
option_ids = [g['options'][0]['id'] for g in product.get('specGroups', []) if not g.get('multiSelect') and g.get('options')]
for g in product.get('specGroups', []):
    if g.get('multiSelect') and g.get('options'):
        option_ids.append(g['options'][0]['id'])
        break
step('[3] spec options resolvable (AC-02)', len(option_ids) >= 1, 'optionIds=%s' % option_ids)

# ---------- 4. 下单（AC-02 计价 / AC-03 结算链路） ----------
st, r = api('POST', '/api/customer/orders', customer,
            {'items': [{'productId': product['id'], 'optionIds': option_ids, 'quantity': 2}], 'remark': 'smoke'})
d = r.get('data') or {}
order_id = d.get('id')
ok = (r.get('code') == 0 and order_id and d.get('status') == 'PENDING_PAYMENT'
      and is_amount(d.get('totalAmount')) and d.get('orderNo') and d.get('expireAt')
      and len(d.get('items') or []) == 1)
step('[4] create order (AC-02/AC-03)', ok,
     'orderId=%s total=%s expireAt=%s' % (order_id, d.get('totalAmount'), d.get('expireAt')))
if not ok:
    print('M1_GATE_FAIL')
    sys.exit(1)
timeout_delta = datetime.strptime(d['expireAt'], TIME_FMT) - datetime.now()

# ---------- 5. 支付（AC-04 PAID） ----------
st, r = api('POST', '/api/customer/orders/%d/pay' % order_id, customer)
d = r.get('data') or {}
pickup = d.get('pickupCode')
ok = r.get('code') == 0 and d.get('status') == 'PAID' and bool(pickup) and d.get('payChannel')
step('[5] pay => PAID + pickupCode (AC-04)', ok, 'pickupCode=%s channel=%s' % (pickup, d.get('payChannel')))

# ---------- 6. 轮询轻量状态（AC-04） ----------
st, r = api('GET', '/api/customer/orders/%d/status' % order_id, customer)
d = r.get('data') or {}
ok = set(d.keys()) == {'status', 'pickupCode', 'seq'} and d.get('status') == 'PAID' and d.get('pickupCode') == pickup
step('[6] status polling contract (AC-04)', ok, json.dumps(d, ensure_ascii=False))

# ---------- 7. 重复支付拦截（AC-04 状态机） ----------
st, r = api('POST', '/api/customer/orders/%d/pay' % order_id, customer)
step('[7] duplicate pay => 1004 (AC-04)', r.get('code') == 1004, 'code=%s' % r.get('code'))

# ---------- 8. 商家登录 + 看板（AC-04 商家侧） ----------
st, r = api('POST', '/api/admin/login', body={'username': ADMIN_USER, 'password': ADMIN_PASSWORD})
ok = r.get('code') == 0 and r.get('data', {}).get('token')
step('[8] admin login', ok, 'code=%s' % r.get('code'))
if not ok:
    print('M1_GATE_FAIL')
    sys.exit(1)
admin = r['data']['token']

st, r = api('GET', '/api/admin/orders/board', admin)
b = r.get('data') or {}
card = [x for x in b.get('pending', []) if x.get('orderId') == order_id]
ok = r.get('code') == 0 and bool(card) and card[0].get('pickupCode') == pickup and 'today' in b
step('[9] board shows new paid order (AC-04)', ok, json.dumps(card[0], ensure_ascii=False) if card else 'MISSING')

# ---------- 9. 开始制作 → PREPARING（AC-04） ----------
st, r = api('POST', '/api/admin/orders/%d/start' % order_id, admin)
step('[10] start => PREPARING (AC-04)', r.get('code') == 0 and r.get('data', {}).get('status') == 'PREPARING',
     'status=%s' % r.get('data', {}).get('status'))

st, r = api('GET', '/api/customer/orders/%d/status' % order_id, customer)
step('[11] customer sees PREPARING (AC-04)', r.get('data', {}).get('status') == 'PREPARING',
     'status=%s' % r.get('data', {}).get('status'))

# ---------- 10. 出餐 → COMPLETED（AC-04） ----------
st, r = api('POST', '/api/admin/orders/%d/complete' % order_id, admin)
ok = r.get('code') == 0 and r.get('data', {}).get('status') == 'COMPLETED' and r.get('data', {}).get('completedAt')
step('[12] complete => COMPLETED (AC-04)', ok, 'status=%s' % r.get('data', {}).get('status'))

st, r = api('GET', '/api/customer/orders/%d/status' % order_id, customer)
step('[13] customer sees COMPLETED (AC-04)', r.get('data', {}).get('status') == 'COMPLETED',
     'status=%s' % r.get('data', {}).get('status'))

# ---------- 11. 超时关单（AC-05）：造单 → 等调度关闭 ----------
if SKIP_TIMEOUT:
    print('SKIP [14] timeout auto-close (AC-05) —— SMOKE_SKIP_TIMEOUT=1')
else:
    st, r = api('POST', '/api/customer/orders', customer,
                {'items': [{'productId': product['id'], 'optionIds': option_ids, 'quantity': 1}], 'remark': 'smoke-timeout'})
    timeout_order = r.get('data', {}).get('id')
    ok = r.get('code') == 0 and timeout_order and r['data'].get('status') == 'PENDING_PAYMENT'
    step('[14a] create pending order for timeout (AC-05)', ok, 'orderId=%s' % timeout_order)

    # 从 expireAt 反推当前配置的支付超时；等待 = 超时 + 调度周期 60s + 15s 缓冲
    wait_seconds = int(timeout_delta.total_seconds()) + 60 + 15
    if wait_seconds > 200:
        print('SKIP [14b] timeout auto-close needs %ds (>200s，生产 15 分钟配置请用 SMOKE_SKIP_TIMEOUT=1)'
              % wait_seconds)
    else:
        print('INFO [14b] waiting up to %ds for scheduler to close the order...' % wait_seconds)
        deadline = time.time() + wait_seconds
        closed = False
        while time.time() < deadline:
            st, r = api('GET', '/api/customer/orders/%d/status' % timeout_order, customer)
            if r.get('data', {}).get('status') == 'CLOSED':
                closed = True
                break
            time.sleep(5)
        step('[14b] pending order auto-closed (AC-05)', closed,
             'orderId=%s status=%s' % (timeout_order, r.get('data', {}).get('status')))

# ---------- 12. 可选的日志 ERROR 检查 ----------
if LOG_FILE:
    try:
        with open(LOG_FILE, 'r', encoding='utf-8', errors='ignore') as f:
            log_text = f.read()
        errors = [line for line in log_text.splitlines() if ' ERROR ' in line]
        step('[15] no ERROR in backend log', not errors, '%d error line(s)' % len(errors))
        for line in errors[:5]:
            print('   ERROR> ' + line.strip())
    except OSError as e:
        print('WARN 日志文件不可读，跳过 ERROR 检查：%s' % e)

# ---------- 汇总 ----------
print('---')
print('SMOKE TOTAL %d  PASS %d  FAIL %d' % (passed + len(failed), passed, len(failed)))
if failed:
    print('FAILED STEPS:', failed)
    print('M1_GATE_FAIL')
    sys.exit(1)
print('M1_GATE_PASS')
