package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.mapper.DefenseAnswersMapper;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.mapper.DefenseScoreRecordMapper;
import com.ai_helper.ai_helper.mapper.DefenseStudentQuestionsMapper;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DefenseResumeVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.AiTextUtils;
import com.ai_helper.ai_helper.util.UploadUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DefenseRecordsServiceImpl implements DefenseRecordsService {

    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;

    @Resource
    private DefenseStudentQuestionsMapper defenseStudentQuestionsMapper;

    @Resource
    private DefenseAnswersMapper defenseAnswersMapper;

    @Resource
    private DefenseScoreRecordMapper scoreRecordMapper;

    @Resource
    private DefenseTopicsMapper defenseTopicsMapper;

    @Resource
    private AppProperties appProperties;


    @Override
    public Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<DefenseRecordsVo> records = defenseRecordsMapper.getDefenseRecords(null);
        PageInfo<DefenseRecordsVo> pageInfo = new PageInfo<>(records);
        return Result.success(pageInfo);
    }

    @Override
    public Result<DetailRecordsVo> getDefenseDetailRecords(Integer defenseId) {

        DetailRecordsVo detailRecords = defenseRecordsMapper.getDetailRecords(defenseId);
        if (detailRecords == null) {
            return Result.error("没有该记录");
        }
        return Result.success(detailRecords);
    }

    @Override
    public Result<List<QuestionDetailVo>> getDefenseQuestionsAnswers(Integer defenseId) {
        List<QuestionDetailVo> list = defenseRecordsMapper.getDefenseQuestionsAnswers(defenseId);
        if (list == null) {
            return Result.error("没有该记录");
        }
        return Result.success(list);
    }

    @Override
    public Result<PageInfo<DefenseRecordsVo>> selectDefenseRecords(DefenseRecordsDto defenseRecordsDto) {

        int pageNum = defenseRecordsDto.getPageNum() != null ? defenseRecordsDto.getPageNum() : 1;
        int pageSize = defenseRecordsDto.getPageSize() != null ? defenseRecordsDto.getPageSize() : 10;


        PageHelper.startPage(pageNum,pageSize);
        List<DefenseRecordsVo> list = defenseRecordsMapper.getDefenseRecords(defenseRecordsDto);
        PageInfo<DefenseRecordsVo> defenseRecordsVoPageInfo = new PageInfo<>(list);
        return Result.success(defenseRecordsVoPageInfo);

    }

    @Override
    public Result<PageInfo<DefenseRecordsVo>> getStudentDefenseRecords(int pageNum, int pageSize, String userNumber) {
        PageHelper.startPage(pageNum, pageSize);
        List<DefenseRecordsVo> list = defenseRecordsMapper.getStudentDefenseRecords(userNumber);

        PageInfo<DefenseRecordsVo> pageInfo = new PageInfo<>(list);
        return Result.success(pageInfo);

    }

    @Override
    public Result<List<DefenseTopics>> getDefenseTopic() {
        List<DefenseTopics> list = defenseRecordsMapper.getDefenseTopic();
        if (list == null) {
            return Result.error("暂无答辩题目");
        }
        return Result.success(list);
    }

    /**
     * 逐轮五维评分明细（2026-10-01 记录详情增强）。
     *
     * <p>直接复用 {@link DefenseScoreRecordMapper#getScoreRecordsByDefenseId}，
     * 不新增 SQL；无数据时返回空列表而不是报错，前端据此决定是否展示汇总区块。</p>
     */
    @Override
    public Result<List<DefenseScoreRecord>> getScoreDetail(Integer defenseId) {
        if (defenseId == null) {
            return Result.error("答辩记录ID不能为空");
        }
        List<DefenseScoreRecord> records = scoreRecordMapper.getScoreRecordsByDefenseId(defenseId);
        return Result.success(records == null ? new ArrayList<>() : records);
    }

    @Override
    public TextQuery getDefenseWordsRecords(Integer topicId) {

        return defenseRecordsMapper.getDetailWordsRecords(topicId);

    }

    @Override
    public TextQuery selectVideoAndPptWords(Integer topicId, String userId) {
        return defenseRecordsMapper.selectVideoAndPptWords(topicId,userId);
    }

    /**
     * 保存一轮「AI 追问」的题目与回答（多表写入）。
     *
     * <p>【N20 · 2026-09-28】补事务 + 异常上抛：原实现无事务、catch 后 return null 吞掉异常，
     * 中途失败会留下半截数据（有题目没答案），且上层完全无感知。现在要么都写、要么都不写。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer saveAiQuestion(List<Integer> existingQuestionIds, Integer topicId, String userId, String userInput, String aiResponse, Double score, String feedback,String summary) {
        try {
            log.info("开始保存AI生成的额外问题 - topicId: {}, userId: {}, 得分: {}", topicId, userId, score);

            //保存总结到defense_records
            if (summary != null) {
                int result = defenseRecordsMapper.saveSummary(userId, topicId, summary);
                if  (result > 0) {
                    log.info("✅ 答辩总结保存成功");
                } else {
                    log.warn("⚠️ 答辩总结保存失败");
                }
            }
            
            if (topicId == null || userId == null) {
                log.warn("topicId或userId为空，无法保存问题");
                return null;
            }
            
            if (aiResponse == null || aiResponse.trim().isEmpty()) {
                log.warn("AI回复内容为空，不保存");
                return null;
            }
            
            Integer defenseId = getOrCreateDefenseRecord(topicId, userId);
            
            if (defenseId == null) {
                // 【N20】定位不到答辩记录属于异常路径：上抛让事务回滚，不再"记一行日志当没事发生"
                throw new BusinessException("未能定位答辩记录，保存中止");
            }
            
            int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
            
            DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
            studentQuestion.setDefenseId(defenseId);
            studentQuestion.setQuestionId(null);
            studentQuestion.setCustomQuestion(aiResponse);
            studentQuestion.setCustomStandardAnswer(userInput != null ? userInput : "");
            studentQuestion.setQuestionType("ai");
            studentQuestion.setSort(nextSort);
            studentQuestion.setCreatedAt(LocalDateTime.now());
            
            int result = defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
            
            if (result > 0) {
                log.info("✅ AI额外问题保存成功到 defense_student_questions - sqId: {}, defenseId: {}, sort: {}", 
                        studentQuestion.getSqId(), defenseId, nextSort);
                
                DefenseAnswers answer = new DefenseAnswers();
                answer.setDefenseId(defenseId);
                answer.setQuestionId(null);
                answer.setSqId(studentQuestion.getSqId());
                answer.setStudentAnswer(userInput != null ? userInput : "");
                answer.setFeedback(feedback);
                answer.setScore(score != null ? new java.math.BigDecimal(score) : null);
                answer.setCreatedAt(LocalDateTime.now());
                
                int answerResult = defenseAnswersMapper.insertAnswer(answer);
                if (answerResult > 0) {
                    log.info("✅ 学生回答保存成功到 defense_answers - answerId: {}, sqId: {}, 得分: {}", 
                            answer.getAnswerId(), studentQuestion.getSqId(), score);
                } else {
                    log.warn("⚠️ 学生回答保存失败");
                }
                
                return studentQuestion.getSqId();
            } else {
                log.error("❌ AI额外问题保存失败");
                return null;
            }

        } catch (Exception e) {
            // 【N20】不再静默吞掉：记录上下文后上抛，让 @Transactional 回滚、上层可见
            log.error("保存AI追问与回答失败（已回滚）- topicId: {}, userId: {}", topicId, userId, e);
            throw new BusinessException("保存追问与回答失败", e);
        }



    }

    
    /**
     * 保存一轮「预设题」的学生回答。
     *
     * <p>【N20 · 2026-09-28】补事务 + 异常上抛：原实现 catch 后静默 return，
     * 既可能留下半截数据、也让上层无法感知失败。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePresetQuestionAnswer(Integer topicId, String userId, Integer questionId, String studentAnswer, String aiFeedback, Double score) {
        try {
            log.info("开始保存预设问题回答 - topicId: {}, userId: {}, questionId: {}, 得分: {}", topicId, userId, questionId, score);
            
            if (topicId == null || userId == null || questionId == null) {
                log.warn("必要参数为空，无法保存");
                return;
            }
            
            Integer defenseId = getOrCreateDefenseRecord(topicId, userId);
            
            if (defenseId == null) {
                log.error("无法获取或创建答辩记录");
                return;
            }
            
            DefenseAnswers answer = new DefenseAnswers();
            answer.setDefenseId(defenseId);
            answer.setQuestionId(questionId);
            answer.setSqId(null);
            answer.setStudentAnswer(studentAnswer != null ? studentAnswer : "");
            answer.setFeedback(aiFeedback);
            answer.setScore(score != null ? new java.math.BigDecimal(score) : null);
            answer.setCreatedAt(LocalDateTime.now());
            
            int result = defenseAnswersMapper.insertAnswer(answer);
            
            if (result > 0) {
                log.info("✅ 预设问题回答保存成功 - answerId: {}, questionId: {}, 得分: {}", answer.getAnswerId(), questionId, score);
            } else {
                log.error("❌ 预设问题回答保存失败");
            }

            
        } catch (Exception e) {
            // 【N20】不再静默吞掉：记录上下文后上抛，让 @Transactional 回滚、上层可见
            log.error("保存预设题回答失败（已回滚）- topicId: {}, userId: {}, questionId: {}",
                    topicId, userId, questionId, e);
            throw new BusinessException("保存预设题回答失败", e);
        }
    }
    
    @Override
    public Integer getOrCreateLatestDefenseRecord(Integer topicId, String userNumber, boolean createIfMissing) {
        if (topicId == null || userNumber == null || userNumber.trim().isEmpty()) {
            return null;
        }
        try {
            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber.trim());
            if (internalUserId == null) {
                log.warn("定位答辩记录失败：未找到用户 {}", userNumber);
                return null;
            }
            Integer defenseId = defenseRecordsMapper.getLatestDefenseIdByUserAndTopic(internalUserId, topicId);
            if (defenseId != null) {
                return defenseId;
            }
        } catch (Exception e) {
            log.error("查询最新答辩记录失败 - userNumber: {}, topicId: {}", userNumber, topicId, e);
        }
        return createIfMissing ? getOrCreateDefenseRecord(topicId, userNumber) : null;
    }

    @Override
    public Integer getOrCreateDefenseRecord(Integer topicId, String userId) {
        try {
            if (userId == null || userId.trim().isEmpty()) {
                log.warn("userId为空");
                return null;
            }

            String userNumber = userId.trim();

            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber);

            if (internalUserId == null) {
                log.error("未找到用户: {}", userNumber);
                return null;
            }

            // 每次答辩独立成一条记录：取该学生该课题进行中（pending）的最新一条
            // （新记录由开始答辩时的 clear 接口创建），兜底：无进行中记录时才在此创建
            Integer defenseId = defenseRecordsMapper.getDefenseIdByUserAndTopic(internalUserId, topicId);

            if (defenseId == null) {
                defenseRecordsMapper.createDefenseRecord(internalUserId, topicId);
                defenseId = defenseRecordsMapper.getDefenseIdByUserAndTopic(internalUserId, topicId);
            }

            if (defenseId != null) {
                log.info("获取/创建答辩记录成功 - defenseId: {}, userId: {}", defenseId, internalUserId);
                return defenseId;
            } else {
                log.error("获取答辩记录失败 - userId: {}, topicId: {}", internalUserId, topicId);
                return null;
            }

        } catch (Exception e) {
            log.error("获取或创建答辩记录时发生异常", e);
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DefenseResumeVo startOrResumeDefenseRecord(Integer topicId, String userNumber,
                                                      int presetQuestionCount, int totalRounds, boolean resume) {
        DefenseResumeVo vo = emptyResumeVo(totalRounds);
        try {
            if (topicId == null || UploadUtils.isBlank(userNumber)) {
                log.warn("开始/继续答辩：topicId或userId为空，跳过创建");
                return vo;
            }

            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber.trim());
            if (internalUserId == null) {
                log.warn("开始/继续答辩：未找到用户 {}", userNumber);
                return vo;
            }

            // 【2026-09-28 回归修复】只有用户在前端弹窗里确认「继续作答」才复用旧场次；
            // 默认一律「清理空壳 + 新建」，保证答辩一定从第 1 题开始。
            // 上一版只判「pending + 有作答」就自动续答，把历史遗留的脏 pending 记录
            // （如 defenseId=293：9-26 已答满 10 轮却没收尾）当成了续答目标，
            // 学生一进答辩页就直接跳到追问/收尾阶段。
            if (resume) {
                Integer resumableId = findResumableDefenseId(topicId, userNumber, totalRounds);
                if (resumableId != null) {
                    int answeredCount = scoreRecordMapper.countByDefenseId(resumableId);
                    vo.setResumed(true);
                    vo.setResumable(true);
                    vo.setDefenseId(resumableId);
                    vo.setAnsweredCount(answeredCount);
                    vo.setRoundNum(answeredCount + 1);
                    fillResumeDetail(vo, resumableId, topicId, presetQuestionCount);
                    log.info("按用户选择续答既有场次 - defenseId: {}, 已答: {} 轮, 当前第: {} 轮, 当前题: {}",
                            resumableId, answeredCount, vo.getRoundNum(), vo.getCurrentQuestion());
                    return vo;
                }
                log.info("请求续答，但当前不存在可续答场次（无记录 / 已答满 / 超出续答窗口），改用新答辩");
            }

            // —— 全新答辩：清理一题未答的空壳 + 新建 ——
            int removed = defenseRecordsMapper.deleteEmptyShellRecords(internalUserId, topicId);
            defenseRecordsMapper.createDefenseRecord(internalUserId, topicId);
            vo.setDefenseId(defenseRecordsMapper.getDefenseIdByUserAndTopic(internalUserId, topicId));
            log.info("开始新答辩：清理空壳记录 {} 条，已创建本次答辩记录 - defenseId: {}, topicId: {}, userId: {}",
                    removed, vo.getDefenseId(), topicId, internalUserId);
            return vo;

        } catch (Exception e) {
            log.error("开始/继续答辩时发生异常 - topicId: {}, userNumber: {}", topicId, userNumber, e);
            return vo;
        }
    }

    @Override
    public DefenseResumeVo detectResumableDefenseRecord(Integer topicId, String userNumber,
                                                        int presetQuestionCount, int totalRounds) {
        DefenseResumeVo vo = emptyResumeVo(totalRounds);
        try {
            Integer resumableId = findResumableDefenseId(topicId, userNumber, totalRounds);
            if (resumableId == null) {
                return vo;
            }
            int answeredCount = scoreRecordMapper.countByDefenseId(resumableId);
            vo.setResumable(true);
            vo.setDefenseId(resumableId);
            vo.setAnsweredCount(answeredCount);
            vo.setRoundNum(answeredCount + 1);
            vo.setTotalRounds(totalRounds);
            fillResumeDetail(vo, resumableId, topicId, presetQuestionCount);
            log.info("探测到可续答场次 - defenseId: {}, 已答: {} 轮", resumableId, answeredCount);
        } catch (Exception e) {
            log.warn("探测可续答场次失败 - topicId: {}, userNumber: {}", topicId, userNumber, e);
        }
        return vo;
    }

    /**
     * 判定是否存在「可续答」的场次，返回其 defenseId；不可续答返回 null。
     *
     * <p>三个条件缺一不可：
     * ① 存在进行中（pending）记录；
     * ② 已作答轮数落在 (0, totalRounds) 区间 —— 已答满的记录不该"接着答"，
     * 它们要么已收尾、要么是没收尾的脏数据；
     * ③ 最后一次作答距现在不超过 {@code app.defense.resume-window-minutes}（默认 30 分钟）——
     * 隔了很久的场次按新答辩处理，避免历史数据"劫持"答辩开端。</p>
     */
    private Integer findResumableDefenseId(Integer topicId, String userNumber, int totalRounds) {
        if (topicId == null || UploadUtils.isBlank(userNumber)) {
            return null;
        }
        String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber.trim());
        if (internalUserId == null) {
            return null;
        }
        Integer defenseId = defenseRecordsMapper.getDefenseIdByUserAndTopic(internalUserId, topicId);
        if (defenseId == null) {
            return null;
        }
        int answeredCount = scoreRecordMapper.countByDefenseId(defenseId);
        if (answeredCount <= 0 || answeredCount >= totalRounds) {
            return null;
        }
        LocalDateTime lastAnsweredAt = scoreRecordMapper.getLastScoreTime(defenseId);
        if (lastAnsweredAt == null) {
            return null;
        }
        long minutesAgo = java.time.Duration.between(lastAnsweredAt, LocalDateTime.now()).toMinutes();
        if (minutesAgo > appProperties.getDefense().getResumeWindowMinutes()) {
            log.info("未完成场次超出续答窗口（{} 分钟前，窗口 {} 分钟），按新答辩处理 - defenseId: {}",
                    minutesAgo, appProperties.getDefense().getResumeWindowMinutes(), defenseId);
            return null;
        }
        return defenseId;
    }

    /** 空结果：默认「不续答、从第 1 题开始」 */
    private DefenseResumeVo emptyResumeVo(int totalRounds) {
        DefenseResumeVo vo = new DefenseResumeVo();
        vo.setResumed(false);
        vo.setResumable(false);
        vo.setAnsweredCount(0);
        vo.setRoundNum(1);
        vo.setTotalRounds(totalRounds);
        return vo;
    }

    /**
     * 组装续答所需的历史：每轮题目 / 回答 / 点评 / 五维分，以及「当前该答哪道题」。
     *
     * <p>以 {@code defense_score_record} 为轮次骨架（每次作答恰落一行、按 round_num 升序），
     * 答案按 question_id（预设题）/ 作答顺序（追问）匹配 —— 即使个别答案行缺失（历史遗留），
     * 也不会让后续轮次整体错位。</p>
     */
    private void fillResumeDetail(DefenseResumeVo vo, Integer defenseId, Integer topicId, int presetQuestionCount) {
        // ① 题库文本：预设题按 question_id 对应（N52 修复后预设题恒为题库题）
        Map<Integer, String> presetQuestionById = new HashMap<>();
        List<String> presetQuestionOrder = new ArrayList<>();
        try {
            List<DefenseQuestions> questions = defenseTopicsMapper.getDefenseQuestionById(topicId);
            if (questions != null) {
                for (DefenseQuestions q : questions) {
                    presetQuestionById.put(q.getQuestionId(), q.getQuestion());
                    presetQuestionOrder.add(q.getQuestion());
                }
            }
        } catch (Exception e) {
            log.warn("回灌记忆：读取题库失败 - topicId: {}", topicId, e);
        }

        // ② 答案行：预设题（question_id 非空）与追问（sq_id 非空）分开归档
        List<DefenseAnswers> presetAnswers = new ArrayList<>();
        List<DefenseAnswers> followAnswers = new ArrayList<>();
        try {
            List<DefenseAnswers> answers = defenseAnswersMapper.getAnswersByDefenseId(defenseId);
            if (answers != null) {
                for (DefenseAnswers a : answers) {
                    if (a.getQuestionId() != null) {
                        presetAnswers.add(a);
                    } else {
                        followAnswers.add(a);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("回灌记忆：读取答案失败 - defenseId: {}", defenseId, e);
        }

        // ③ 追问登记表：sq_id → 可读题目；有的入库点存的是整段 AI 回复，用 AiTextUtils 收敛成题目
        Map<Integer, String> followQuestionBySqId = new LinkedHashMap<>();
        String latestFollowQuestion = null;
        try {
            List<DefenseStudentQuestions> registered = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            if (registered != null) {
                for (DefenseStudentQuestions dsq : registered) { // 已按 sort 升序
                    String q = AiTextUtils.cleanQuestionText(dsq.getCustomQuestion());
                    if (q != null) {
                        followQuestionBySqId.put(dsq.getSqId(), q);
                        latestFollowQuestion = q; // 顺序遍历，留下的是最新登记的追问
                    }
                }
            }
        } catch (Exception e) {
            log.warn("回灌记忆：读取追问题目失败 - defenseId: {}", defenseId, e);
        }

        // ④ 逐轮组装（追问按「已遍历到的第几个追问」对上答案，不依赖 round_num 连续）
        List<DefenseScoreRecord> scoreRecords = scoreRecordMapper.getScoreRecordsByDefenseId(defenseId);
        if (scoreRecords == null) {
            scoreRecords = new ArrayList<>();
        }
        List<DefenseResumeVo.Round> rounds = new ArrayList<>();
        int followSeen = 0;
        for (DefenseScoreRecord sr : scoreRecords) {
            DefenseResumeVo.Round r = new DefenseResumeVo.Round();
            int round = sr.getRoundNum();
            r.setRoundNum(round);
            r.setComment(sr.getComment());
            r.setExpression(toDouble(sr.getExpressionScore()));
            r.setLogic(toDouble(sr.getLogicScore()));
            r.setProfessional(toDouble(sr.getProfessionalScore()));
            r.setAdaptability(toDouble(sr.getAdaptabilityScore()));
            r.setInnovation(toDouble(sr.getInnovationScore()));
            r.setTotalScore(sumScores(r));

            if (round <= presetQuestionCount) {
                r.setQuestion(presetQuestionById.get(sr.getQuestionId()));
                DefenseAnswers presetAnswer = findPresetAnswer(presetAnswers, sr.getQuestionId(), round);
                if (presetAnswer != null) {
                    r.setStudentAnswer(presetAnswer.getStudentAnswer());
                }
            } else {
                if (followSeen < followAnswers.size()) {
                    DefenseAnswers followAnswer = followAnswers.get(followSeen);
                    r.setStudentAnswer(followAnswer.getStudentAnswer());
                    if (r.getQuestion() == null) {
                        r.setQuestion(followQuestionBySqId.get(followAnswer.getSqId()));
                    }
                }
                followSeen++;
            }
            rounds.add(r);
        }
        vo.setRounds(rounds);

        // ⑤ 当前待答题：预设题阶段取题库第 N 道；追问阶段取「最新登记的追问」
        //    （上一轮回复里的『下一题:』会被 submitIfNewQuestion 登记成一条追问题）
        int nextRound = vo.getAnsweredCount() + 1;
        if (nextRound <= presetQuestionCount) {
            vo.setCurrentQuestion(presetQuestionOrder.size() >= nextRound
                    ? presetQuestionOrder.get(nextRound - 1) : null);
        } else {
            vo.setCurrentQuestion(latestFollowQuestion);
        }
    }

    /** 预设题答案：优先按 question_id 匹配；老数据缺 question_id 时按轮次下标兜底 */
    private DefenseAnswers findPresetAnswer(List<DefenseAnswers> presetAnswers, Integer questionId, int round) {
        if (questionId != null) {
            for (DefenseAnswers a : presetAnswers) {
                if (questionId.equals(a.getQuestionId())) {
                    return a;
                }
            }
        }
        int idx = round - 1;
        return (idx >= 0 && idx < presetAnswers.size()) ? presetAnswers.get(idx) : null;
    }

    private Double toDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** 总分 = 五维之和（与落库/展示口径一致；五维全空时返回 null） */
    private Double sumScores(DefenseResumeVo.Round r) {
        Double[] dims = {r.getExpression(), r.getLogic(), r.getProfessional(), r.getAdaptability(), r.getInnovation()};
        double sum = 0;
        boolean hasAny = false;
        for (Double v : dims) {
            if (v != null) {
                sum += v;
                hasAny = true;
            }
        }
        return hasAny ? sum : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishDefenseRecord(Integer defenseId, java.math.BigDecimal totalScore, String summary) {
        try {
            if (defenseId == null) {
                log.warn("答辩收尾：defenseId为空，跳过总分落库");
                return;
            }
            int result = defenseRecordsMapper.updateFinalResult(defenseId, totalScore, summary);
            if (result > 0) {
                log.info("✅ 答辩总分落库成功 - defenseId: {}, 总分: {}", defenseId, totalScore);
            } else {
                log.warn("⚠️ 答辩总分落库失败，未找到记录 - defenseId: {}", defenseId);
            }
        } catch (Exception e) {
            log.error("❌ 答辩总分落库时发生异常 - defenseId: {}", defenseId, e);
        }
    }


}
