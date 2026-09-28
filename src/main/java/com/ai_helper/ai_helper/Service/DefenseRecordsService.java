package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DefenseResumeVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface DefenseRecordsService {

    /**
     * 获取 defense_history 表中的数据
     *
     * @return defense_history 表中的数据
     */
    Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(Integer pageNum, Integer pageSize);

    Result<DetailRecordsVo> getDefenseDetailRecords(Integer defenseId);

    Result<List<QuestionDetailVo>> getDefenseQuestionsAnswers(Integer defenseId);

    Result<PageInfo<DefenseRecordsVo>> selectDefenseRecords(DefenseRecordsDto defenseRecordsDto);

    Result<PageInfo<DefenseRecordsVo>> getStudentDefenseRecords(int pageNum, int pageSize, String userNumber);

    Result<List<DefenseTopics>> getDefenseTopic();

    TextQuery getDefenseWordsRecords(Integer topicId);

    TextQuery selectVideoAndPptWords(Integer topicId, String userId);

    Integer saveAiQuestion(List<Integer> existingQuestionIds, Integer topicId, String userId, String userInput, String aiResponse, Double score, String feedback, String summary);

    void savePresetQuestionAnswer(Integer topicId, String userId, Integer questionId, String studentAnswer, String aiFeedback, Double score);

    Integer getOrCreateDefenseRecord(Integer topicId, String userId);

    /**
     * 取该用户在该题目下的最新答辩记录（不区分状态），不存在时按需创建。
     *
     * <p>用于附件（视频/报告）的读写：附件应挂到已有记录上，
     * 而不是像 {@link #getOrCreateDefenseRecord} 那样、记录已完成时再新建一条空壳。</p>
     *
     * @param createIfMissing 不存在时是否新建；删除类操传 false，避免为了删文件而凭空建记录
     * @return 答辩记录 ID，定位不到且不创建时返回 null
     */
    Integer getOrCreateLatestDefenseRecord(Integer topicId, String userNumber, boolean createIfMissing);

    /**
     * 开始或继续一场答辩（2026-09-28 N1）。
     *
     * <p><b>默认一定是从第 1 题开始</b>：只有调用方显式传 {@code resume = true}
     * （即用户在前端弹窗里选了「继续作答」），且确实存在「可续答」的场次时，
     * 才复用旧记录并返回续答信息（已答轮数、当前待答题、历史轮次），由调用方回灌会话记忆后接着答；
     * 否则一律「清理一题未答的空壳 + 新建本次答辩记录」。</p>
     *
     * <p>「可续答」的三个必要条件见 {@link #detectResumableDefenseRecord}。</p>
     *
     * <p>历史教训：旧实现（{@code startNewDefenseRecord}）删完空壳后无条件新建，
     * 导致答了 3 题中途退出再进会从第 1 题重来、原记录卡在 pending；
     * 而 2026-09-28 第一版续答又过于宽松（只判 pending + 有作答），
     * 把历史遗留的脏 pending 记录当成续答目标，一进答辩页就跳到追问阶段 —— 故加双重限制 + 用户确认。</p>
     *
     * @param presetQuestionCount 本场预设题数量（题库题数）
     * @param totalRounds         本场总轮次（预设题数 + 追问额度）
     * @param resume              true = 用户已确认继续作答；false = 从第 1 题开始
     * @return 续答/新建结果；参数不合法等异常情况返回 {@code resumed = false} 的空结果
     */
    DefenseResumeVo startOrResumeDefenseRecord(Integer topicId, String userNumber,
                                               int presetQuestionCount, int totalRounds, boolean resume);

    /**
     * 只读探测：是否存在「可续答」的答辩场次（<b>不写任何数据</b>）。
     *
     * <p>供前端进入答辩页时先问一次：有可续答场次 → 弹窗让用户选「继续作答 / 重新开始」；
     * 用户点「继续作答」才调 {@code startOrResumeDefenseRecord(..., resume = true)}。
     * 这样答辩开端默认干净，历史脏数据不会把人直接带进追问阶段。</p>
     *
     * <p>「可续答」需同时满足：① 存在进行中（pending）记录；② 已作答轮数在 (0, totalRounds) 之间；
     * ③ 最后一次作答在 {@code app.defense.resume-window-minutes}（默认 30 分钟）之内。</p>
     *
     * @return {@code resumable = true} 且带场次信息；无可续答时 {@code resumable = false}
     */
    DefenseResumeVo detectResumableDefenseRecord(Integer topicId, String userNumber,
                                                 int presetQuestionCount, int totalRounds);

    /**
     * 答辩结束收尾：聚合总分（0-50制）与总结评语写回 defense_records
     */
    void finishDefenseRecord(Integer defenseId, java.math.BigDecimal totalScore, String summary);
}
