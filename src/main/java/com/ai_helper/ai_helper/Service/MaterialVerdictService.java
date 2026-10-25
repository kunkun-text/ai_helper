package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.MaterialVerdict;

/**
 * 【2026-10-25】通用「材料资格裁定」：判断一份学生提交的内容**能不能作为本课题的答辩材料**。
 *
 * <p><b>为什么独立成一个服务（而不是写进报告评析里）：</b>
 * 报告文档、视频转写、以及将来任何"学生提交的东西"，第一步都应该是同一个问题——
 * 这份东西是什么、能不能拿去评。把它抽出来之后：
 * <ul>
 *   <li>判定口径只有一份，不会出现"报告判得严、视频判得松"；</li>
 *   <li>换模型/调 prompt/加缓存只改一个类；</li>
 *   <li>调用方只消费 {@link MaterialVerdict.Kind} 四个语义分类，**不维护任何文件类型词表**。</li>
 * </ul>
 *
 * <p><b>方向安全</b>：未启用、调用失败、输出不可解析一律返回
 * {@link MaterialVerdict.Kind#UNKNOWN}，调用方必须按"合格"继续——
 * 裁定没跑成不能把合格材料压成不合格。</p>
 */
public interface MaterialVerdictService {

    /**
     * 裁定一份材料。
     *
     * @param topicName 课题名（可为空——为空时模型只能按"是否像答辩材料"判断）
     * @param material  材料正文（报告全文 / 视频转写等，调用方自行保证已做长度上限）
     * @return 永远非 {@code null}；无法判断时返回 {@link MaterialVerdict#unknown(String)}
     */
    MaterialVerdict judge(String topicName, String material);
}
