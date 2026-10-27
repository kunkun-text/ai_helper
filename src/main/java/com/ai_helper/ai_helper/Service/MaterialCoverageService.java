package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.MaterialCoverage;

import java.util.List;

/**
 * 【2026-10-27】材料方向判定：课题预设题 ↔ 提交材料的覆盖关系。
 *
 * <p><b>为什么单独成一个服务：</b>它完全不依赖报告链路——判定输入只有
 * 「课题名 + 课题描述 + 预设题清单 + 材料文本」，输出只有逐题命中。
 * 报告文档、视频转写、将来任何学生提交的材料都走同一个实现，
 * 代码里不出现任何具体学科词，**A/B/C 课题通用**。</p>
 *
 * <p><b>为什么判定预设题：</b>预设题是课题本身要求阐释的内容。
 * 一份材料如果一道都没讲，就不是这个课题的材料；这比"看起来像不像报告"可靠
 * （2026-10-27 {@code defenseId=377}：思政调研作业"像报告"，但对预设题 0 命中）。</p>
 */
public interface MaterialCoverageService {

    /**
     * 本地模型逐题判定（一次调用，多行输出）。
     *
     * @param topicName        课题名
     * @param topicDescription 课题描述（可为空）
     * @param questions        该课题的预设题（为空 → {@link MaterialCoverage#notApplicable()}）
     * @param material         材料正文（内部按配置截断，保头保尾）
     * @return 逐题命中结果；调用失败返回 {@link MaterialCoverage#unknown}（调用方按待复审处理）
     */
    MaterialCoverage judge(String topicName, String topicDescription, List<String> questions, String material);

    /**
     * 云端复核（只在本地"0 命中但有漏行"时调用）。
     *
     * @return 可解析的结果；未配置云端 / 调用失败 / 仍有漏行 → {@code null}
     */
    MaterialCoverage reviewWithCloud(String topicName, String topicDescription,
                                     List<String> questions, String material);
}
