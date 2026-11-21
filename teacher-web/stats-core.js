/**
 * 成绩统计纯函数（无 DOM 依赖，浏览器与 node 共用，便于单测）。
 * 口径与小程序 / 后端保持一致：
 *   - 等级阈值 = utils/scoreCard.js gradeLevel = DocScoreParser.gradeLevel
 *   - 主分数 = 逐轮总分 > 0 ? 逐轮总分 : 报告评析分（teacher.js mapDefenseRecord 的 displayScore 口径）
 *   - 平均分 = ROUND(AVG(score), 1)，仅统计已出分记录（StatsMapper.selectOverview 口径）
 *   - 合格率 = 合格及以上（≥15）/ 已出分场次；优秀率 = ≥35 / 已出分场次
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.StatsCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const GRADES = ['优秀', '良好', '合格', '不合格', '无效'];
  const DIM_KEYS = ['expressionScore', 'logicScore', 'professionalScore', 'adaptabilityScore', 'innovationScore'];

  function r1(x) { return Math.round(x * 10) / 10; }

  function gradeLevel(total) {
    if (total >= 35) return '优秀';
    if (total >= 26) return '良好';
    if (total >= 15) return '合格';
    if (total >= 5) return '不合格';
    return '无效';
  }

  /** 列表主分数：与 teacher.js mapDefenseRecord 同口径；无分返回 null */
  function displayScore(rec) {
    const s = Number(rec.score);
    if (isFinite(s) && s > 0) return s;
    const d = rec.reportDocScore;
    if (d !== null && d !== undefined && isFinite(Number(d))) return Number(d);
    // 文档答辩材料裁定不合格 → 不出分、待复审，不计入统计
    if (rec.hasReport) return null;
    return rec.status === 'completed' && isFinite(s) ? s : null;
  }

  function scored(records) {
    return records.map(displayScore).filter((v) => v !== null);
  }

  function mean(arr) { return arr.length ? r1(arr.reduce((a, b) => a + b, 0) / arr.length) : null; }

  function median(arr) {
    if (!arr.length) return null;
    const s = arr.slice().sort((a, b) => a - b);
    const m = Math.floor(s.length / 2);
    return r1(s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2);
  }

  function stddev(arr) {
    if (arr.length < 2) return null;
    const m = arr.reduce((a, b) => a + b, 0) / arr.length;
    return r1(Math.sqrt(arr.reduce((a, b) => a + (b - m) * (b - m), 0) / (arr.length - 1)));
  }

  function rate(n, d) { return d ? r1(n * 100 / d) : null; }

  function overview(records, topics, students) {
    const s = scored(records);
    return {
      totalDefenses: records.length,
      completedDefenses: records.filter((r) => r.status === 'completed').length,
      pendingDefenses: records.filter((r) => r.status === 'pending').length,
      avgScore: mean(s),
      totalTopics: topics.length,
      totalStudents: students,
      medianScore: median(s),
      stdScore: stddev(s),
      maxScore: s.length ? Math.max.apply(null, s) : null,
      passRate: rate(s.filter((v) => v >= 15).length, s.length),
      excellentRate: rate(s.filter((v) => v >= 35).length, s.length),
      scoredCount: s.length
    };
  }

  function gradeDistribution(records) {
    const out = {}; GRADES.forEach((g) => { out[g] = 0; });
    scored(records).forEach((v) => { out[gradeLevel(v)]++; });
    return GRADES.map((g) => ({ grade: g, count: out[g] }));
  }

  /** 直方图：[0,5) [5,10) … [45,50]，满分 50 归入最后一段 */
  function histogram(records, width) {
    const w = width || 5;
    const n = Math.ceil(50 / w);
    const bins = Array.from({ length: n }, (_, i) => ({ from: i * w, to: Math.min(50, (i + 1) * w), count: 0 }));
    scored(records).forEach((v) => {
      const idx = Math.min(n - 1, Math.max(0, Math.floor(v / w)));
      bins[idx].count++;
    });
    return bins;
  }

  /** 五维均值：所有记录逐轮行的平均（与 radar.js averageDims 的“逐轮平均”口径一致） */
  function dimAverages(records) {
    const sums = [0, 0, 0, 0, 0]; let cnt = 0;
    records.forEach((rec) => (rec.rounds || []).forEach((row) => {
      DIM_KEYS.forEach((k, i) => { const v = Number(row[k]); sums[i] += isFinite(v) ? v : 0; });
      cnt++;
    }));
    return sums.map((s) => (cnt ? r1(s / cnt) : 0));
  }

  /** 按课题统计（StatsMapper.selectTopicStats 字段 + 合格率/优秀率） */
  function topicStats(records, topics, teacherName) {
    return topics.map((t) => {
      const rs = records.filter((r) => r.topicId === t.topicId);
      const s = scored(rs);
      return {
        topicId: t.topicId,
        topicName: t.topicName,
        teacherName: teacherName || '',
        defenseTime: t.defenseTime,
        questionCount: t.questionCount,
        defenseCount: rs.length,
        completedCount: rs.filter((r) => r.status === 'completed').length,
        avgScore: mean(s),
        passRate: rate(s.filter((v) => v >= 15).length, s.length),
        excellentRate: rate(s.filter((v) => v >= 35).length, s.length)
      };
    }).sort((a, b) => b.defenseCount - a.defenseCount || a.topicId - b.topicId);
  }

  /** 近 N 天趋势（含 0 场的日期，便于折线连续） */
  function dailyTrend(records, today, days) {
    const out = [];
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(today.getTime() - i * 86400000);
      const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
      const rs = records.filter((r) => String(r.defenseTime).slice(0, 10) === key);
      out.push({ day: key, cnt: rs.length, avgScore: mean(scored(rs)) });
    }
    return out;
  }

  /** CSV 单元格：公式注入中和（CWE-1236，与后端 CsvUtils 同思路）+ 引号转义 */
  function csvCell(v) {
    let s = v === null || v === undefined ? '' : String(v);
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
    if (/[",\r\n]/.test(s)) s = '"' + s.replace(/"/g, '""') + '"';
    return s;
  }

  function toCsv(header, rows) {
    return '\uFEFF' + [header].concat(rows).map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
  }

  return { GRADES, DIM_KEYS, gradeLevel, displayScore, overview, gradeDistribution, histogram, dimAverages, topicStats, dailyTrend, csvCell, toCsv, mean, median, stddev };
});
