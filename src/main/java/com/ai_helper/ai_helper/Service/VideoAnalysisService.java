package com.ai_helper.ai_helper.Service;

import java.util.Map;

/**
 * 答辩视频的 AI 评分（2026-10-20 · VID 系列）。
 *
 * <p><b>【里程碑 · 10.19 视频答辩开端】（用户口径标注，代码落盘于 10-20）</b></p>
 *
 * <p>这一天是「视频答辩」正式接入 AI 评分的起点。在此之前，视频只是上传后归档到
 * {@code defense_records.video_url}，不参与任何评分；从这一版起，视频里的说话内容会被
 * 转成文字并进入五维评分，与「文字答辩」「语音答辩」「文档答辩」并列，成为第四条评分链路。
 * 后续再改视频链路（换模型、改分档、调 prompt）时，请以本注释为时间锚点回看
 * {@code CHANGES.md} 与 {@code 新阶段路线图-2026-10-15.md} 的 VID-0 ~ VID-4 条目。</p>
 *
 * <p><b>本次纳入版本库的相关文件（提交/推送前逐项确认，漏一个链路就断）：</b></p>
 * <ul>
 *   <li>后端评分链路：{@code Service/VideoAnalysisService}、{@code Service/Impl/VideoAnalysisServiceImpl}、
 *       {@code Controller/teacher/VideoAnalysisController}、{@code util/MaterialScorePrompts}
 *       （报告评析与视频评分共用同一套评分提示词，改一处两端同时变）；</li>
 *   <li>后端语音链路：{@code Service/AsrService}、{@code Service/Impl/WhisperAsrServiceImpl}
 *       （新增按文件路径抽音轨转写，避免把几百 MB 视频读进堆）；</li>
 *   <li>后端上传与落库：{@code Service/ChunkUploadService(Impl)}
 *       （{@code /api/video/init} 增 {@code defenseId} 显式绑定本场）、
 *       {@code Controller/student/VideoUploadController}、{@code Controller/chatController}
 *       （{@code /api/chat/clear} 回传本场 {@code defenseId}）、
 *       {@code mapper/DefenseRecordsMapper} + {@code Mapper/DefenseRecordsMapper.xml}、
 *       {@code pojo/vo/DefenseRecordsVo}；</li>
 *   <li>后端配置：{@code Config/AppProperties}、{@code application.yml.example}
 *       （{@code app.video-analysis.*}，含开关/预算/术语表；缺失时走默认值）；</li>
 *   <li>前端：{@code utils/gradeSheet.js}、{@code utils/scoreCard.js}（成绩单解析，学生端/教师端/报告评析三处共用）、
 *       {@code utils/uploader.js}、{@code pages/defense/*}（答辩页视频上传条）、
 *       {@code pages/teacher/*}（触发按钮 + 轮询 + 五维卡片 + 列表红标）、
 *       {@code pages/student/*}（只读成绩单 + 列表红标）、{@code app.wxss}（共用标签样式）；</li>
 *   <li>文档：{@code CHANGES.md}、{@code 新阶段路线图-2026-10-15.md}、{@code 需求清单-2026-09-15.md}。</li>
 * </ul>
 *
 * <p><b>【务必同步仓库】</b>本批文件必须整体提交并推送到远端
 * （{@code git@github.com:kunkun-text/ai_helper.git}，分支 {@code master}）。
 * 视频链路是「前端按钮 → 后端接口 → Mapper 字段 → 成绩单解析」四段串联，
 * 只提交其中一部分会出现「按钮点得动但后端 500」「能评分但列表不标红」这类半残状态，
 * 排查成本远高于一次推全。推送前建议先跑 {@code mvn -o test}（当前 81/81）与
 * {@code node --check} 校验前端页面脚本。</p>
 *
 * <p><b>链路：</b>ffmpeg 抽音轨 → whisper 整段转写（{@code video_words}）→
 * 五维点评 → 五维评分 → 服务端守护 → 成绩单文本（{@code video_analysis}）。</p>
 *
 * <p><b>与报告评析的关系：</b>两者产出**同一形态的成绩单**（总分/50 + 等级 + 逐维依据），
 * 等级尺子与评分提示词完全共用（见 {@code MaterialScorePrompts}），
 * 教师和学生看到的是同一套口径，只是材料从「文档」换成「视频里的说话内容」。</p>
 *
 * <p><b>为什么必须异步：</b>10 分钟音频在 8G 低功耗机上要跑十几分钟到半小时，
 * 同步接口会直接被网关/微信客户端判超时。</p>
 */
public interface VideoAnalysisService {

    /**
     * 触发某场答辩的视频评分。
     *
     * @param defenseId 答辩记录 ID
     * @param force     true = 忽略已有结果重新跑（教师手动重跑）；false = 有结果且视频未变则直接返回
     * @return 任务状态（与 {@link #status(Integer)} 同结构）
     */
    Map<String, Object> trigger(Integer defenseId, boolean force);

    /**
     * 查询某场答辩的视频评分状态。
     *
     * @return stage / stageText / totalScore / analysis / updatedAt / error 等
     */
    Map<String, Object> status(Integer defenseId);
}
