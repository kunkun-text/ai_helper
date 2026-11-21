// 小程序纯函数回归测试（node 内置 test runner，无需安装依赖）
// 用法：node --test tools/test-miniapp-utils.js
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const AI = path.join(__dirname, '../src/main/resources/static/Ai');
const JAVA = path.join(__dirname, '../src/main/java/com/ai_helper/ai_helper');
const req = (p) => require(path.join(AI, p));

const scoreCard = req('utils/scoreCard.js');
const gradeSheet = req('utils/gradeSheet.js');
const verdict = req('utils/materialVerdict.js');
const radar = req('utils/radar.js');
const protocol = req('utils/protocol.js');

test('等级阈值：前端 scoreCard.gradeLevel 与后端 DocScoreParser.gradeLevel 边界一致', () => {
  const cases = [[50, '优秀'], [35, '优秀'], [34.9, '良好'], [26, '良好'], [25.9, '合格'], [15, '合格'], [14.9, '不合格'], [5, '不合格'], [4.9, '无效'], [0, '无效']];
  for (const [s, g] of cases) assert.equal(scoreCard.gradeLevel(s), g, 'score=' + s);
  const java = fs.readFileSync(path.join(JAVA, 'util/DocScoreParser.java'), 'utf8');
  const body = java.slice(java.indexOf('public static String gradeLevel'));
  const pairs = [...body.matchAll(/totalScore >= (\d+)\)\s*\{\s*return "(\S+?)"/g)].slice(0, 4).map((m) => [Number(m[1]), m[2]]);
  assert.deepEqual(pairs, [[35, '优秀'], [26, '良好'], [15, '合格'], [5, '不合格']]);
});

test('scoreCard：逐轮总分=五维之和，汇总取逐轮平均，脏数据按 0', () => {
  const rows = [
    { roundNum: 1, expressionScore: 6, logicScore: 7, professionalScore: 8, adaptabilityScore: 5, innovationScore: 4, comment: ' 好 ' },
    { roundNum: 2, expressionScore: '8', logicScore: null, professionalScore: 'abc', adaptabilityScore: 6, innovationScore: 6 }
  ];
  const cards = scoreCard.buildRoundCards(rows);
  assert.equal(cards[0].total, 30);
  assert.equal(cards[0].grade, '良好');
  assert.equal(cards[0].comment, '好');
  assert.equal(cards[1].total, 20);
  assert.equal(cards[0].dims[2].percent, 80);
  const sum = scoreCard.buildSummary(rows);
  assert.equal(sum.total, 25);
  assert.equal(sum.totalText, '25.0');
  assert.equal(sum.grade, '合格');
  assert.equal(sum.roundCount, 2);
  assert.equal(sum.dims[0].avg, 7);
  assert.equal(scoreCard.buildSummary([]), null);
  assert.equal(scoreCard.buildSummary(null), null);
});

test('gradeSheet.parse：解析后端成绩单文本（总分/等级/五维/警告/引擎）', () => {
  const text = '【基于答辩视频】\n语音转写：共 1832 字\n评分引擎：云端 GLM\n总分：31/50　等级：良好\n表达（6分）：语速偏快\n⚠️ 应变（3分）：未提及风险\n⚠️ 疑似跑题\n逻辑(7分):清楚';
  const r = gradeSheet.parse(text);
  assert.deepEqual(r.summary, { total: '31', grade: '良好', warnings: ['疑似跑题'] });
  assert.equal(r.engine, '云端 GLM');
  assert.equal(r.dims.length, 3);
  assert.deepEqual(r.dims[1], { name: '应变', score: '3', comment: '未提及风险', missing: true });
  assert.equal(r.dims[2].name, '逻辑');
  const empty = gradeSheet.parse('');
  assert.equal(empty.summary, null);
  const junk = gradeSheet.parse('随便一段文字');
  assert.equal(junk.summary, null);
  assert.equal(junk.raw, '随便一段文字'); // 解析失败不丢原文
});

test('materialVerdict：文案映射与状态', () => {
  assert.equal(verdict.label('suspect_offtopic'), '疑似跑题·待复审');
  assert.equal(verdict.shortLabel('unclear'), '待复审');
  assert.equal(verdict.isRejected('ok'), false);
  assert.equal(verdict.isRejected('insufficient'), true);
  assert.equal(verdict.state('ok'), 'reviewed');
  assert.equal(verdict.state('insufficient'), 'pending');
  assert.equal(verdict.state(''), '');
  assert.equal(verdict.label('unknown'), '');
});

test('radar.averageDims：五维逐轮平均，长度恒为 5', () => {
  const avg = radar.averageDims([
    { expressionScore: 6, logicScore: 8, professionalScore: 10, adaptabilityScore: 4, innovationScore: 2 },
    { expressionScore: 8, logicScore: 6, professionalScore: 6, adaptabilityScore: 6, innovationScore: 4 }
  ]);
  assert.equal(avg.length, 5);
  assert.deepEqual(avg.map((v) => Math.round(v * 10) / 10), [7, 7, 8, 5, 3]);
  const none = radar.averageDims([]);
  assert.deepEqual(none, [0, 0, 0, 0, 0]);
});

test('AI 协议标签：前端 protocol.js 与后端 AiProtocolConstants 逐字节一致', () => {
  const java = fs.readFileSync(path.join(JAVA, 'constant/AiProtocolConstants.java'), 'utf8');
  const get = (name) => (java.match(new RegExp('String ' + name + ' = "([^"]*)"')) || [])[1];
  for (const k of ['COMMENT_TAG', 'SCORE_TAG', 'NEXT_QUESTION_TAG', 'SUMMARY_TAG', 'MARK_ON_TOPIC', 'MARK_WRONG', 'MARK_OFF_TOPIC']) {
    assert.equal(protocol[k], get(k), k);
  }
});

test('stats 页 withPercent：条形图相对宽度（最大=100，非零最小 4%，全零为 0）', () => {
  let pageDef = null;
  global.Page = (def) => { pageDef = def; };
  global.wx = { getStorageSync: () => '', showToast() {}, request() {}, navigateBack() {} };
  delete require.cache[path.join(AI, 'pages/stats/stats.js')];
  req('pages/stats/stats.js');
  const out = pageDef.withPercent([{ c: 50 }, { c: 1 }, { c: '25' }, { c: null }], 'c');
  assert.deepEqual(out.map((x) => x.percent), [100, 4, 50, 4]);
  assert.deepEqual(pageDef.withPercent([{ c: 0 }], 'c').map((x) => x.percent), [0]);
  assert.deepEqual(pageDef.withPercent([], 'c'), []);
});

test('config.getBaseUrl：三环境与 prod 强制 HTTPS', () => {
  const config = req('utils/config.js');
  const env0 = config.env;
  config.env = 'dev';
  assert.equal(config.getBaseUrl(), config.devBaseUrl.replace(/\/$/, ''));
  config.env = 'prod'; config.prodBaseUrl = 'http://x.com';
  assert.throws(() => config.getBaseUrl());
  config.prodBaseUrl = 'https://x.com/';
  assert.equal(config.getBaseUrl(), 'https://x.com');
  config.env = 'bad';
  assert.throws(() => config.getBaseUrl());
  config.env = env0; config.prodBaseUrl = '';
});

// ---------------- 教师端网页演示：统计口径 ----------------
const SC = require(path.join(__dirname, '../teacher-web/stats-core.js'));

test('teacher-web 等级阈值与小程序 scoreCard 完全一致', () => {
  for (let s = 0; s <= 50; s += 0.5) assert.equal(SC.gradeLevel(s), scoreCard.gradeLevel(s), 's=' + s);
});

test('teacher-web 主分数口径 = teacher.js mapDefenseRecord.displayScore', () => {
  assert.equal(SC.displayScore({ score: 31.2, reportDocScore: 40 }), 31.2);
  assert.equal(SC.displayScore({ score: 0, reportDocScore: 40, hasReport: true }), 40);
  assert.equal(SC.displayScore({ score: 0, reportDocScore: null, hasReport: true, status: 'completed' }), null); // 裁定不合格不出分
  assert.equal(SC.displayScore({ score: 0, status: 'completed' }), 0); // 跑题按 0 分
  assert.equal(SC.displayScore({ score: 0, status: 'pending' }), null);
});

test('teacher-web 概览 / 等级分布 / 直方图 / 合格率', () => {
  const recs = [
    { score: 40, status: 'completed', topicId: 1 }, { score: 30, status: 'completed', topicId: 1 },
    { score: 20, status: 'completed', topicId: 2 }, { score: 10, status: 'completed', topicId: 2 },
    { score: 50, status: 'pending', topicId: 2 }, { score: 0, status: 'pending', topicId: 2 }
  ];
  const ov = SC.overview(recs, [{}, {}], 9);
  assert.equal(ov.totalDefenses, 6); assert.equal(ov.completedDefenses, 4); assert.equal(ov.pendingDefenses, 2);
  assert.equal(ov.scoredCount, 5); assert.equal(ov.avgScore, 30); assert.equal(ov.medianScore, 30);
  assert.equal(ov.passRate, 80); assert.equal(ov.excellentRate, 40); assert.equal(ov.maxScore, 50);
  assert.deepEqual(SC.gradeDistribution(recs).map((g) => g.count), [2, 1, 1, 1, 0]);
  const h = SC.histogram(recs, 5);
  assert.equal(h.length, 10); assert.equal(h[9].count, 1); assert.equal(h[8].count, 1); // 50 归入 45–50
  const ts = SC.topicStats(recs, [{ topicId: 1, topicName: 'A', questionCount: 5 }, { topicId: 2, topicName: 'B', questionCount: 3 }], 'T');
  assert.equal(ts[0].topicId, 2); assert.equal(ts[0].defenseCount, 4); assert.equal(ts[0].avgScore, 26.7);
  assert.equal(ts[1].avgScore, 35); assert.equal(ts[1].passRate, 100);
});

test('teacher-web 五维均值与 CSV 公式注入中和', () => {
  const avg = SC.dimAverages([{ rounds: [{ expressionScore: 6, logicScore: 8, professionalScore: 10, adaptabilityScore: 4, innovationScore: 2 }] }, { rounds: [{ expressionScore: 8, logicScore: 6, professionalScore: 6, adaptabilityScore: 6, innovationScore: 4 }] }]);
  assert.deepEqual(avg, [7, 7, 8, 5, 3]);
  assert.equal(SC.csvCell('=HYPERLINK("x")'), '"\'=HYPERLINK(""x"")"');
  assert.equal(SC.csvCell('-1'), "'-1");
  assert.equal(SC.csvCell('张三'), '张三');
  const csv = SC.toCsv(['a', 'b'], [[1, 'x,y']]);
  assert.ok(csv.startsWith('\uFEFFa,b\r\n1,"x,y"\r\n'));
});

test('teacher-web 模拟数据：字段齐全、分数在 0~50、学号不与真实学号格式冲突', () => {
  global.window = {};
  require(path.join(__dirname, '../teacher-web/data.js'));
  const M = global.window.MOCK;
  assert.ok(M.records.length > 100);
  for (const r of M.records) {
    for (const k of ['defenseRecordId', 'userName', 'userNumber', 'topicName', 'score', 'status', 'defenseTime']) assert.ok(k in r, k);
    assert.ok(r.score >= 0 && r.score <= 50);
    assert.ok(/^2026D/.test(r.userNumber));
    for (const row of r.rounds) SC.DIM_KEYS.forEach((k) => assert.ok(row[k] >= 0 && row[k] <= 10));
  }
});
