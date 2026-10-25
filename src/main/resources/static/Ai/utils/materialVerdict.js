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
  insufficient: '内容不足·待复审'
};

/** 短文案（学生端列表用，避免一行塞太多字） */
const SHORT_LABELS = {
  suspect_offtopic: '疑似跑题',
  suspect_nonmaterial: '非答辩材料',
  insufficient: '内容不足'
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

module.exports = {
  label: label,
  shortLabel: shortLabel,
  isRejected: isRejected,
  LABELS: LABELS,
  SHORT_LABELS: SHORT_LABELS
};
