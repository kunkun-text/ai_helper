/**
 * 【2026-10-25】材料裁定枚举 → 界面文案（两端共用）。
 *
 * 后端 `MaterialVerdict.code()` 只回四个稳定枚举：
 *   ok / suspect_offtopic / suspect_nonmaterial / insufficient
 *
 * 页面**不要**再自己判断"是不是简历""是不是跑题"——那是后端裁定的职责。
 * 这里只负责把枚举翻译成中文，改文案只改这一处。
 */

/** 列表标签（带"待复审"动作提示，教师端用） */
const LABELS = {
  suspect_offtopic: '疑似跑题·待复审',
  suspect_nonmaterial: '非答辩材料·待复审',
  insufficient: '内容不足·待复审',
  // 【2026-10-27】判不准（位移/漏行/云端也不可用）：按用户口径不出分、待复审
  unclear: '无法判定·待复审'
};

/** 短文案（学生端列表用，避免一行塞太多字） */
const SHORT_LABELS = {
  suspect_offtopic: '疑似跑题',
  suspect_nonmaterial: '非答辩材料',
  insufficient: '内容不足',
  unclear: '待复审'
};

/**
 * @param {string} code 后端 materialVerdict
 * @returns {string} 标签文案；ok / 空值时返回 ''（不显示标签）
 */
function label(code) {
  return LABELS[code] || '';
}

/**
 * @param {string} code 后端 materialVerdict
 * @returns {string} 短文案；ok / 空值时返回 ''
 */
function shortLabel(code) {
  return SHORT_LABELS[code] || '';
}

/** 是否处于"待复审"状态（裁定不合格，未出分） */
function isRejected(code) {
  return !!LABELS[code];
}

/**
 * 【N87 · 2026-10-27】裁定状态 → 详情页只读展示用，三态：
 *   ''         正常材料（该列为 NULL，正常评分链路从不写裁定列）→ 什么都不显示
 *   'pending'  判过不合格、等教师复审 → 「待复审」+ 类型/理由
 *   'reviewed' 教师云端复审通过（后端把 material_verdict 置 ok）→ 「已复审」
 *
 * 注意：'ok' 目前只由教师「云端复审」写入（ReportDocAnalysisServiceImpl.review），
 * 正常分析不会写 ok——若以后正常链路也开始写 ok，这里必须改成显式 reviewed 标记列。
 *
 * @param {string} code 后端 materialVerdict
 * @returns {''|'pending'|'reviewed'}
 */
function state(code) {
  if (!code) return '';
  if (code === 'ok') return 'reviewed';
  return LABELS[code] ? 'pending' : '';
}

module.exports = {
  label: label,
  shortLabel: shortLabel,
  isRejected: isRejected,
  state: state,
  LABELS: LABELS,
  SHORT_LABELS: SHORT_LABELS
};
