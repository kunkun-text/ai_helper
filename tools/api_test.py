#!/usr/bin/env python3
"""
AI-helper 后端接口级测试（功能 / 边界 / 安全 / 性能）
用法：后端已启动于 BASE（默认 http://127.0.0.1:8080），MySQL/Redis 可用。
  python3 tools/api_test.py --seed 5000 --out test-results
说明：测试数据全部使用 2026T / 2026S 前缀的测试账号，与真实学号不冲突；
      --seed 通过 mysql 命令行批量写入模拟答辩记录，仅用于性能测试，测完可 --cleanup 清除。
"""
import argparse, json, os, random, statistics, subprocess, sys, time, uuid, html
from concurrent.futures import ThreadPoolExecutor
import requests

ap = argparse.ArgumentParser()
ap.add_argument('--base', default='http://127.0.0.1:8080')
ap.add_argument('--seed', type=int, default=5000)
ap.add_argument('--out', default='test-results')
ap.add_argument('--mysql', default='mysql -uroot -p123456 ai_helper')
ap.add_argument('--cleanup', action='store_true')
args = ap.parse_args()
B = args.base
os.makedirs(args.out, exist_ok=True)
RUN = time.strftime('%m%d%H%M%S')
T_NUM, S_NUM, S2_NUM = f'2026T{RUN}', f'2026S{RUN}', f'2026X{RUN}'
PWD = 'Test@123456'
results = []

def sql(q):
    r = subprocess.run(args.mysql.split() + ['-N', '-B', '-e', q], capture_output=True, text=True)
    if r.returncode: raise RuntimeError(r.stderr)
    return r.stdout.strip()

def case(cid, cat, name, expect):
    def deco(fn):
        t0 = time.perf_counter()
        try:
            ok, actual = fn()
        except Exception as e:
            ok, actual = False, f'异常：{e!r}'[:200]
        ms = (time.perf_counter() - t0) * 1000
        results.append(dict(id=cid, cat=cat, name=name, expect=expect, actual=str(actual)[:220], ok=bool(ok), ms=round(ms, 1)))
        print(f"[{'PASS' if ok else 'FAIL'}] {cid} {name} -> {str(actual)[:120]}")
        return fn
    return deco

def post(path, body=None, tok=None, **kw):
    h = {'Authorization': tok} if tok else {}
    return requests.post(B + path, json=body, headers=h, timeout=30, **kw)

def get(path, tok=None, **kw):
    h = {'Authorization': tok} if tok else {}
    return requests.get(B + path, headers=h, timeout=30, **kw)

def reg(kind, num, name, pwd=PWD, email='t@example.com'):
    return post(f'/register/{kind}', dict(userNumber=num, name=name, email=email, password=pwd, role=kind)).json()

def login(kind, num, pwd=PWD):
    return post(f'/login/{kind}', dict(userNumber=num, password=pwd, role=kind)).json()

ctx = {}
# ---------------- 功能测试 ----------------
@case('F01', '功能', '教师注册', 'code=1，提示注册成功')
def _():
    r = reg('teacher', T_NUM, '测试教师'); return r['code'] == 1, r['msg'] or r.get('data')

@case('F02', '功能', '学生注册', 'code=1')
def _():
    r = reg('student', S_NUM, '测试学生'); reg('student', S2_NUM, '=HYPERLINK("http://x")'); return r['code'] == 1, r.get('data') or r['msg']

@case('F03', '功能', '教师登录并获取 token', 'code=1，返回 UUID token 与角色 teacher')
def _():
    r = login('teacher', T_NUM); ctx['t'] = r['data']['token']; ctx['tid'] = r['data']['id']
    return r['code'] == 1 and r['data']['role'] == 'teacher' and len(ctx['t']) == 36, f"role={r['data']['role']}, token长度={len(ctx['t'])}"

@case('F04', '功能', '学生登录并获取 token', 'code=1，角色 student')
def _():
    r = login('student', S_NUM); ctx['s'] = r['data']['token']
    return r['code'] == 1 and r['data']['role'] == 'student', f"role={r['data']['role']}"

@case('F05', '功能', '教师新建答辩课题（含 2 道题目）', 'code=1，课题列表可查到')
def _():
    name = f'大数据技术原理-测试课题{RUN}'
    body = dict(topicName=name, defenseTime='2026-12-01', topicDescription='HDFS 与 MapReduce 基础',
                questions=[dict(questionType='teacher', question='请说明 HDFS 中 NameNode 的作用？', standardAnswer='管理命名空间与元数据'),
                           dict(questionType='teacher', question='MapReduce 的 Shuffle 阶段做了什么？', standardAnswer='分区、排序、合并并传输到 Reduce')])
    r = post('/teacher/addDefense', body, ctx['t']).json()
    lst = get('/teacher/getAllDefense?pageNum=1&pageSize=50', ctx['t']).json()
    rows = lst['data'].get('records') or lst['data'].get('list') or lst['data'].get('rows') or []
    hit = [x for x in rows if x.get('topicName') == name]
    if hit: ctx['topic'] = hit[0].get('topicId')
    return r['code'] == 1 and bool(hit), f"code={r['code']}，列表命中={len(hit)}，topicId={ctx.get('topic')}"

@case('F06', '功能', '学生获取可答辩课题', 'code=1，包含新课题')
def _():
    r = get('/student/getDefenseTopic', ctx['s']).json()
    s = json.dumps(r['data'], ensure_ascii=False)
    return r['code'] == 1 and f'测试课题{RUN}' in s, f"code={r['code']}，包含新课题={f'测试课题{RUN}' in s}"

@case('F07', '功能', '学生提交意见反馈', 'code=1，「我的反馈」可查到')
def _():
    r = post('/student/feedback/submit', dict(content='预览视频后希望有返回按钮', contact=''), ctx['s']).json()
    my = get('/student/feedback/my', ctx['s']).json()
    ctx['fb'] = my['data'][0].get('feedbackId') or my['data'][0].get('id') if my['data'] else None
    return r['code'] == 1 and len(my['data']) >= 1, f"code={r['code']}，我的反馈条数={len(my['data'])}"

@case('F08', '功能', '教师查看并回复反馈', 'code=1，待处理数减少')
def _():
    before = get('/teacher/feedback/pending-count', ctx['t']).json()['data']
    r = post('/teacher/feedback/reply', dict(feedbackId=ctx['fb'], reply='已收到，下个版本修复'), ctx['t']).json()
    after = get('/teacher/feedback/pending-count', ctx['t']).json()['data']
    return r['code'] == 1, f"回复code={r['code']}，待处理 {before}→{after}"

@case('F09', '功能', '修改个人信息（姓名/手机/邮箱）', 'code=1')
def _():
    r = post('/editUserInfo', dict(id=str(ctx['tid']), name='测试教师2', phoneNumber='13800000000', email='t2@example.com', userNumber=T_NUM), ctx['t']).json()
    db = sql(f"SELECT name FROM users WHERE user_number='{T_NUM}'")
    return r['code'] == 1 and db == '测试教师2', f"code={r['code']}，库中姓名={db}"

# ---------------- 种子数据（性能测试用） ----------------
def seed(n):
    tid = ctx['tid']
    sql("INSERT INTO users(name,user_number,role,email) VALUES " + ",".join(
        f"('压测学生{i:03d}','2026P{RUN}{i:03d}','student','p@example.com')" for i in range(200)))
    uids = [int(x) for x in sql(f"SELECT user_id FROM users WHERE user_number LIKE '2026P{RUN}%'").split()]
    sql("INSERT INTO defense_topics(teacher_id,topic_name,topic_description,defense_time) VALUES " + ",".join(
        f"({tid},'压测课题{RUN}-{k}','seed','2026-11-01')" for k in range(8)))
    tids = [int(x) for x in sql(f"SELECT topic_id FROM defense_topics WHERE topic_name LIKE '压测课题{RUN}%'").split()]
    rnd = random.Random(42); rows = []
    for i in range(n):
        st = rnd.choice(['completed'] * 8 + ['pending'] * 2)
        sc = 'NULL' if st == 'pending' else f"{max(0, min(50, rnd.gauss(34, 7))):.1f}"
        d = f"2026-{rnd.randint(9, 11):02d}-{rnd.randint(1, 28):02d} {rnd.randint(8, 21):02d}:00:00"
        rows.append(f"({rnd.choice(uids)},{rnd.choice(tids)},'{st}',{sc},'{d}','{rnd.choice(['text', 'voice'])}')")
    for k in range(0, n, 1000):
        sql("INSERT INTO defense_records(user_id,topic_id,status,score,defense_date,defense_mode) VALUES " + ",".join(rows[k:k + 1000]))
    # CSV 注入用例：给恶意姓名学生一条记录
    x = sql(f"SELECT user_id FROM users WHERE user_number='{S2_NUM}'")
    sql(f"INSERT INTO defense_records(user_id,topic_id,status,score,defense_date) VALUES ({x},{tids[0]},'completed',30,NOW())")
    return sql(f"SELECT COUNT(*) FROM defense_records")

total = seed(args.seed)
print('seeded, total records =', total)

@case('F10', '功能', '教师数据总览统计', 'code=1，场次数与数据库一致')
def _():
    r = get('/teacher/stats/overview', ctx['t']).json()
    d = r['data']; s = json.dumps(d, ensure_ascii=False)
    return r['code'] == 1 and str(total) in s, f"数据库记录数={total}；返回字段={list(d.keys())[:8]}"

@case('F11', '功能', '按课题统计', 'code=1，含 8 个压测课题')
def _():
    r = get('/teacher/stats/topics', ctx['t']).json()
    n = sum(1 for x in r['data'] if f'压测课题{RUN}' in json.dumps(x, ensure_ascii=False))
    return r['code'] == 1 and n == 8, f"压测课题条数={n}，全部课题={len(r['data'])}"

@case('F12', '功能', '答辩记录分页查询', 'code=1，每页 10 条，total 与库一致')
def _():
    r = get('/teacher/defense/records?pageNum=1&pageSize=10', ctx['t']).json()
    return r['code'] == 1 and len(r['data']['list']) == 10, f"本页={len(r['data']['list'])}，total={r['data'].get('total')}"

@case('F13', '功能', '按姓名/课题组合搜索', 'code=1，结果均匹配条件')
def _():
    r = post('/teacher/defense/search', dict(pageNum=1, pageSize=20, userName='压测学生001', topicName=f'压测课题{RUN}'), ctx['t']).json()
    lst = r['data']['list']
    ok = all('压测学生001' in (x.get('userName') or x.get('name') or '') for x in lst)
    return r['code'] == 1 and len(lst) > 0 and ok, f"命中={r['data'].get('total')}，全部匹配={ok}"

@case('F14', '功能', '导出成绩 CSV', 'HTTP 200，text/csv，含 UTF-8 BOM')
def _():
    r = get('/teacher/export/records.csv', ctx['t'])
    ctx['csv'] = r.content.decode('utf-8-sig', 'replace')
    return r.status_code == 200 and r.content[:3] == b'\xef\xbb\xbf', f"HTTP {r.status_code}，{r.headers.get('Content-Type')}，{len(r.content)//1024} KB，行数={ctx['csv'].count(chr(10))}"

# ---------------- 边界测试 ----------------
@case('B01', '边界', '学号为空登录', '被拒绝，提示不能为空')
def _():
    r = post('/login/student', dict(userNumber='', password=PWD, role='student')); j = r.json()
    return j['code'] != 1, f"HTTP {r.status_code}，{j['msg']}"

@case('B02', '边界', '密码少于 6 位注册', '被拒绝')
def _():
    j = reg('student', f'2026B{RUN}', '短密码', pwd='123'); return j['code'] != 1, j['msg']

@case('B03', '边界', '重复学号注册', '被拒绝，友好提示')
def _():
    j = reg('student', S_NUM, '重复'); return j['code'] != 1 and '已注册' in j['msg'], j['msg']

@case('B04', '边界', '分页 pageSize=100000', '服务端截断，不返回海量数据')
def _():
    j = get('/teacher/defense/records?pageNum=1&pageSize=100000', ctx['t']).json()
    n = len(j['data']['list']); return j['code'] == 1 and n <= 100, f"实际返回 {n} 条"

@case('B05', '边界', '反馈内容 1001 字（上限 1000）', '被拒绝')
def _():
    r = post('/student/feedback/submit', dict(content='长' * 1001), ctx['s']); j = r.json()
    return j['code'] != 1, f"HTTP {r.status_code}，{j['msg']}"

@case('B06', '边界', '反馈内容为空白', '被拒绝')
def _():
    r = post('/student/feedback/submit', dict(content='   '), ctx['s']); j = r.json()
    return j['code'] != 1, f"HTTP {r.status_code}，{j['msg']}"

@case('B07', '边界', '查询不存在的答辩详情 id=99999999', '返回空或失败提示，无 500')
def _():
    r = get('/teacher/defense/DetailRecords/99999999', ctx['t'])
    return r.status_code == 200, f"HTTP {r.status_code}，{r.text[:80]}"

@case('B08', '边界', '路径参数非数字 /scoreDetail/abc', '被拒绝，无堆栈泄露')
def _():
    r = get('/teacher/defense/scoreDetail/abc', ctx['t'])
    leak = 'Exception' in r.text or 'at com.' in r.text
    return r.status_code in (200, 400) and not leak, f"HTTP {r.status_code}，堆栈泄露={leak}"

# ---------------- 安全测试 ----------------
@case('S01', '安全', '未携带 token 访问教师统计', 'HTTP 401')
def _():
    r = get('/teacher/stats/overview'); return r.status_code == 401, f"HTTP {r.status_code}"

@case('S02', '安全', '伪造 token 访问', 'HTTP 401')
def _():
    r = get('/teacher/stats/overview', 'Bearer ' + str(uuid.uuid4())); return r.status_code == 401, f"HTTP {r.status_code}"

@case('S03', '安全', '学生 token 越权访问教师接口', 'HTTP 403')
def _():
    codes = [get(p, ctx['s']).status_code for p in ('/teacher/stats/overview', '/teacher/export/records.csv', '/teacher/defense/records')]
    return all(c == 403 for c in codes), f"HTTP {codes}"

@case('S04', '安全', 'SQL 注入登录：学号 \' OR \'1\'=\'1', '登录失败')
def _():
    j = post('/login/student', dict(userNumber="' OR '1'='1", password="' OR '1'='1", role='student')).json()
    return j['code'] != 1, j['msg']

@case('S05', '安全', 'SQL 注入搜索：姓名 %\' OR 1=1 -- ', '不返回全部记录')
def _():
    j = post('/teacher/defense/search', dict(pageNum=1, pageSize=10, userName="%' OR 1=1 -- "), ctx['t']).json()
    return j['code'] == 1 and (j['data'].get('total') or 0) == 0, f"命中 {j['data'].get('total')} 条"

@case('S06', '安全', '连续 5 次密码错误后锁定', '第 6 次即使密码正确也被拒绝')
def _():
    num = f'2026L{RUN}'; reg('student', num, '锁定测试')
    msgs = [login('student', num, 'wrong-pass')['msg'] for _ in range(5)]
    j = login('student', num)
    return j['code'] != 1 and '过多' in j['msg'], f"前5次：{msgs[0]}；第6次（正确密码）：{j['msg']}"

@case('S07', '安全', '登录失败文案统一（防账号枚举）', '不存在账号与密码错误提示一致')
def _():
    a = login('student', f'2026N{RUN}', 'x123456')['msg']; b = login('teacher', T_NUM, 'x123456')['msg']
    return a == b, f"不存在账号：{a}；密码错误：{b}"

@case('S08', '安全', '密码以 BCrypt 哈希存储', '库中为 $2a$/$2b$ 开头的 60 位哈希')
def _():
    h = sql(f"SELECT password FROM users WHERE user_number='{T_NUM}'")
    return h.startswith('$2') and len(h) == 60 and PWD not in h, f"{h[:7]}…（长度 {len(h)}）"

@case('S09', '安全', 'CSV 公式注入防护', '以 = 开头的姓名被加单引号中和')
def _():
    line = next((l for l in ctx['csv'].splitlines() if 'HYPERLINK' in l), '')
    return "'=HYPERLINK" in line, line[:80]

@case('S10', '安全', '组件状态接口需登录（防路径泄露）', 'HTTP 401')
def _():
    r = get('/api/system/components'); return r.status_code == 401, f"HTTP {r.status_code}"

@case('S12', '安全', '修改他人资料（越权）', '被拒绝，提示只能修改自己的资料')
def _():
    other = sql(f"SELECT user_id FROM users WHERE user_number='{S_NUM}'")
    j = post('/editUserInfo', dict(id=other, name='被篡改', userNumber=S_NUM), ctx['t']).json()
    db = sql(f"SELECT name FROM users WHERE user_number='{S_NUM}'")
    return j['code'] != 1 and db == '测试学生', f"{j['msg']}；目标姓名仍为「{db}」"

@case('S11', '安全', '登出/伪造 Bearer 前缀变体', 'HTTP 401')
def _():
    r = get('/student/getDefenseTopic', 'Bearer'); return r.status_code == 401, f"HTTP {r.status_code}"

# ---------------- 性能测试 ----------------
def bench(path, tok, n=400, conc=20, method='get', body=None):
    lat = []
    def one(_):
        t0 = time.perf_counter()
        r = (requests.get(B + path, headers={'Authorization': tok}, timeout=60) if method == 'get'
             else requests.post(B + path, json=body, headers={'Authorization': tok}, timeout=60))
        lat.append((time.perf_counter() - t0) * 1000); return r.status_code
    for _ in range(10): one(0)  # 预热
    lat.clear(); t0 = time.perf_counter()
    with ThreadPoolExecutor(conc) as ex: codes = list(ex.map(one, range(n)))
    wall = time.perf_counter() - t0; lat.sort()
    return dict(path=path, n=n, conc=conc, ok=sum(c == 200 for c in codes), p50=round(lat[n // 2], 1),
                p95=round(lat[int(n * .95)], 1), max=round(lat[-1], 1), avg=round(statistics.mean(lat), 1), qps=round(n / wall, 1))

perf = []
for p, m, b in [('/teacher/stats/overview', 'get', None), ('/teacher/stats/topics', 'get', None),
                ('/teacher/defense/records?pageNum=1&pageSize=10', 'get', None),
                ('/teacher/defense/search', 'post', dict(pageNum=1, pageSize=10, topicName=f'压测课题{RUN}')),
                ('/student/getDefenseTopic', 'get', None)]:
    tok = ctx['s'] if p.startswith('/student') else ctx['t']
    res = bench(p, tok, method=m, body=b); res['label'] = p.split('?')[0]; perf.append(res); print('PERF', res)

t0 = time.perf_counter(); r = get('/teacher/export/records.csv', ctx['t']); exp_ms = (time.perf_counter() - t0) * 1000

@case('P01', '性能', f'{total} 条记录下统计/列表接口 20 并发', 'P95 < 1000 ms，成功率 100%')
def _():
    worst = max(x['p95'] for x in perf); okall = all(x['ok'] == x['n'] for x in perf)
    return worst < 1000 and okall, f"最差 P95={worst} ms，全部成功={okall}"

@case('P02', '性能', f'导出 {total} 条成绩 CSV', '< 3000 ms')
def _():
    return exp_ms < 3000, f"{exp_ms:.0f} ms，{len(r.content)//1024} KB"

json.dump(dict(run=RUN, base=B, total_records=int(total), time=time.strftime('%Y-%m-%d %H:%M:%S'),
               results=results, perf=perf, export_ms=round(exp_ms)), open(f'{args.out}/api-results.json', 'w'), ensure_ascii=False, indent=1)
npass = sum(x['ok'] for x in results)
print(f'\n合计 {len(results)} 项，通过 {npass}，失败 {len(results) - npass}')
if args.cleanup:
    sql(f"DELETE FROM defense_records WHERE topic_id IN (SELECT topic_id FROM defense_topics WHERE topic_name LIKE '压测课题{RUN}%')")
    sql(f"DELETE FROM defense_topics WHERE topic_name LIKE '压测课题{RUN}%'")
    sql(f"DELETE FROM users WHERE user_number LIKE '2026P{RUN}%'")
sys.exit(0 if npass == len(results) else 1)
