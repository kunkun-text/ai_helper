/**
 * 评分表成绩单化（2026-10-20）。
 *
 * 背景：`defense_score_record`（评分表）里的逐轮五维分数此前只以一行纯文本展示
 * （「第1轮：表达 6、逻辑 7…」），**`comment`（本轮大模型评语）从未展示**，
 * 也没有总分与等级，与「文档提交 → 报告评析」那套成绩单（总分 + 等级 + 逐项依据）不一致。
 *
 * 本模块把「评分表」对齐到同一套口径：
 *   - 总分：逐轮五维之和的平均（与雷达图的平均口径一致）
 *   - 等级：与后端 `DocScoreParser.gradeLevel` **完全相同**的阈值
 *   - 依据：每轮的 `comment` 就是本地大模型给出的本轮判断
 *
 * 纯函数、无副作用、不依赖 wx API —— 学生端与教师端共用，避免两边各写一份导致口径漂移。
 */

/** 与后端 SCORE_KEYS / radar.js DIM_LABELS 顺序一致 */
const DIM_KEYS = [
  'expressionScore',
  'logicScore',
  'professionalScore',
  'adaptabilityScore',
  'innovationScore'
];

const DIM_LABELS = ['表达', '逻辑', '专业', '应变', '创新'];

/** 单维满分（与评分口径一致：每维 10 分，五维和 50 分） */
const DIM_MAX = 10;

/** 转到数值；脏数据（null / 空串 / 非数字）按 0 处理，避免整块消失 */
function num(value) {
  const n = parseFloat(value);
  return isFinite(n) ? n : 0;
}

function round1(value) {
  return Math.round(value * 10) / 10;
}

/**
 * 等级映射——必须与后端 `DocScoreParser.gradeLevel` 逐字一致：
 * ≥35 优秀 / 26~34 良好 / 15~25 合格 / 5~14 不合格 / <5 无效。
 */
function gradeLevel(total) {
  if (total >= 35) {
    return '优秀';
  }
  if (total >= 26) {
    return '良好';
  }
  if (total >= 15) {
    return '合格';
  }
  if (total >= 5) {
    return '不合格';
  }
  return '无效';
}

/** 单轮总分 = 五维之和（0~50） */
function roundTotal(row) {
  return round1(DIM_KEYS.reduce((sum, key) => sum + num(row[key]), 0));
}

/**
 * 逐轮卡片：[{ roundNum, total, totalText, grade, dims:[{name,score,percent}], comment }]
 *
 * @param {Array} rows `/student/scoreDetail` 或 `/teacher/defense/scoreDetail` 返回的数组
 */
function buildRoundCards(rows) {
  return (rows || []).map((row) => {
    const total = roundTotal(row);
    return {
      roundNum: row.roundNum,
      questionId: row.questionId,
      total: total,
      totalText: total.toFixed(1),
      grade: gradeLevel(total),
      dims: DIM_KEYS.map((key, index) => ({
        name: DIM_LABELS[index],
        score: num(row[key]),
        percent: (num(row[key]) / DIM_MAX) * 100
      })),
      comment: (row.comment || '').trim()
    };
  });
}

/**
 * 整场汇总：{ total, totalText, grade, roundCount, dims:[{name,avg,percent}] }
 *
 * 无数据返回 null（调用方据此决定是否渲染，不显示空壳）。
 */
function buildSummary(rows) {
  const cards = buildRoundCards(rows);
  if (!cards.length) {
    return null;
  }
  const dims = DIM_KEYS.map((key, index) => {
    const avg = round1(cards.reduce((sum, card) => sum + card.dims[index].score, 0) / cards.length);
    return { name: DIM_LABELS[index], avg: avg, percent: (avg / DIM_MAX) * 100 };
  });
  const total = round1(cards.reduce((sum, card) => sum + card.total, 0) / cards.length);
  return {
    total: total,
    totalText: total.toFixed(1),
    grade: gradeLevel(total),
    roundCount: cards.length,
    dims: dims
  };
}

module.exports = {
  DIM_KEYS: DIM_KEYS,
  DIM_LABELS: DIM_LABELS,
  DIM_MAX: DIM_MAX,
  gradeLevel: gradeLevel,
  buildRoundCards: buildRoundCards,
  buildSummary: buildSummary
};
