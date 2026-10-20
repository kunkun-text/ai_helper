/**
 * 成绩单文本解析（2026-10-20）。
 *
 * <p><b>为什么单独抽出来：</b>现在有三处需要「把后端成绩单文本变成五维小卡片」——
 * 报告评析（教师端）、视频评分（教师端）、视频评分（学生端）。原实现只写在
 * `teacher.js` 里，学生端只能显示一大段裸文本，两端观感不一致。</p>
 *
 * <p><b>后端格式（两条链路完全相同）</b>：</p>
 * <pre>
 * 【基于答辩视频】
 * 语音转写：共 1832 字（只分析说话内容，不评价画面）
 * 总分：31/50　等级：良好
 * 表达（6分）：语速偏快但术语准确…
 * ⚠️ 应变（3分）：未提及风险与边界
 * </pre>
 *
 * <p>解析失败时返回空 dims + 原文，由调用方兜底展示，**绝不静默丢内容**。</p>
 */

/** 五维顺序固定，与后端 DocScoreParser.DIMENSIONS 一致 */
const DIM_NAMES = ['表达', '逻辑', '专业', '应变', '创新'];

/**
 * @param {string} text 后端落库的成绩单文本
 * @returns {{summary: ({total:string, grade:string, warnings:string[]}|null), dims: Array, raw: string}}
 */
function parse(text) {
  const raw = text || '';
  const dims = [];
  const warnings = [];
  let total = '';
  let grade = '';
  if (!raw) {
    return { summary: null, dims: dims, raw: raw };
  }

  // 总分与等级同一行：`总分：36/50　等级：优秀`
  const totalMatch = raw.match(/总分\s*[:：]\s*(\d+(?:\.\d+)?)\s*\/\s*50[\s　]*等级\s*[:：]\s*(\S+)/);
  if (totalMatch) {
    total = totalMatch[1];
    grade = totalMatch[2];
  }

  raw.split(/\r?\n/).forEach((line) => {
    const t = (line || '').trim();
    if (!t) {
      return;
    }
    // 维度行：`表达（6分）：…` / `⚠️ 应变（3分）：…`（判定符表示该维自认缺失、分数已被压低）
    const dimMatch = t.match(/^(⚠️\s*)?(表达|逻辑|专业|应变|创新)\s*[（(]\s*(\d+(?:\.\d+)?)\s*分\s*[）)]\s*[:：]\s*(.*)$/);
    if (dimMatch) {
      dims.push({
        name: dimMatch[2],
        score: dimMatch[3],
        comment: dimMatch[4] || '（模型未给出该维度点评）',
        missing: !!dimMatch[1]
      });
      return;
    }
    // 其余 ⚠️ 行（跑题、接地失败）作为成绩单顶部警告；标题行忽略
    if (t.indexOf('⚠️') === 0) {
      warnings.push(t.replace(/^⚠️\s*/, ''));
    }
  });

  return {
    summary: (total || grade) ? { total: total, grade: grade, warnings: warnings } : null,
    dims: dims,
    raw: raw
  };
}

module.exports = {
  DIM_NAMES: DIM_NAMES,
  parse: parse
};
