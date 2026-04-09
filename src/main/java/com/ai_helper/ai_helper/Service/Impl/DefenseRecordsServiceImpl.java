package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.mapper.DefenseAnswersMapper;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.mapper.DefenseStudentQuestionsMapper;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DefenseRecordsServiceImpl implements DefenseRecordsService {

    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;

    @Resource
    private DefenseStudentQuestionsMapper defenseStudentQuestionsMapper;

    @Resource
    private DefenseAnswersMapper defenseAnswersMapper;


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

    @Override
    public TextQuery getDefenseWordsRecords(Integer topicId) {

        return defenseRecordsMapper.getDetailWordsRecords(topicId);

    }

    @Override
    public TextQuery selectVideoAndPptWords(Integer topicId, String userId) {
        return defenseRecordsMapper.selectVideoAndPptWords(topicId,userId);
    }

    @Override
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
                log.error("无法获取或创建答辩记录");
                return null;
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
            log.error("❌ 保存AI问题时发生异常", e);
            return null;
        }



    }

    
    @Override
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
            log.error("❌ 保存预设问题回答时发生异常", e);
        }
    }
    
    @Override
    public Integer getOrCreateDefenseRecord(Integer topicId, String userId) {
        try {
            Integer userIdInt = userId.matches("\\d+") ? Integer.parseInt(userId) : null;
            
            if (userIdInt == null) {
                log.warn("userId格式不正确: {}", userId);
                return null;
            }
            
            Integer existingDefenseId = defenseRecordsMapper.getExistingDefenseRecord(userIdInt, topicId);
            
            if (existingDefenseId != null) {
                log.info("找到已存在的答辩记录 - defenseId: {}", existingDefenseId);
                return existingDefenseId;
            }
            
            int newDefenseId = defenseRecordsMapper.createDefenseRecord(userIdInt, topicId);
            
            if (newDefenseId > 0) {
                log.info("创建新的答辩记录 - defenseId: {}", newDefenseId);
                return newDefenseId;
            } else {
                log.error("创建答辩记录失败");
                return null;
            }
            
        } catch (Exception e) {
            log.error("获取或创建答辩记录时发生异常", e);
            return null;
        }
    }


}
