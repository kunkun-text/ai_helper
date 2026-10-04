/**
 * AI 通信协议常量（F1 · 2026-10-05）。
 *
 * 与后端 `AiProtocolConstants` 一一对应：所有前端解析/拼接协议标签的地方
 * 一律引用这里，禁止在业务代码里散落字面量（prompt 示例文本除外）。
 */

module.exports = {
  /** 点评行标签 */
  COMMENT_TAG: '点评:',
  /** 评分行标签（总分/50|表达|逻辑|专业|应变|创新） */
  SCORE_TAG: '评分:',
  /** 下一题标签（含历史别名"下一问:"） */
  NEXT_QUESTION_TAG: '下一题:',
  NEXT_QUESTION_ALIAS: '下一问:',
  /** 总结标签 */
  SUMMARY_TAG: '总结:',

  /** 切题标记 */
  MARK_ON_TOPIC: '[切题]',
  /** 明显错误标记 */
  MARK_WRONG: '[错误]',
  /** 跑题标记 */
  MARK_OFF_TOPIC: '[跑题]',

  /** 半角/全角冒号（解析容错） */
  COLON_CLASS: '[:：]',
  /** 标签解析正则片段（点评/评分/下一题/总结，兼容全角冒号） */
  TAG_PATTERN: '(点评|评分|下一题|下一问|总结)[:：]'
};
