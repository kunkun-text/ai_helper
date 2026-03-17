package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DefenseTopicsServiceImpl implements DefenseTopicsService {

    @Autowired
    private DefenseTopicsMapper defenseTopicsMapper;

    @Override
    @Transactional
    public Result<Object> addDefense(EditDefenseDto editDefenseDto) {
        try {
            System.out.println("=== 开始添加答辩题目 ===");
            System.out.println("topicName: " + editDefenseDto.getTopicName());
            System.out.println("teacherId: " + editDefenseDto.getTeacherId());
            System.out.println("defenseTime: " + editDefenseDto.getDefenseTime());
            System.out.println("topicDescription: " + editDefenseDto.getTopicDescription());
            System.out.println("questions: " + editDefenseDto.getQuestions());

            // 1. 添加到 defense_topics 表
            DefenseTopics defenseTopics = new DefenseTopics();
            defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
            defenseTopics.setTopicName(editDefenseDto.getTopicName());
            defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());

            // 设置答辩时间（将 String 转为 LocalDateTime）
            if (editDefenseDto.getDefenseTime() != null) {
                defenseTopics.setDefenseTime(String.valueOf(editDefenseDto.getDefenseTimeAsLocalDateTime()));
            } else {
                return Result.error("答辩时间不能为空");
            }

            defenseTopics.setCreatedAt(LocalDateTime.now());
            defenseTopics.setUpdatedAt(LocalDateTime.now());

            int result = defenseTopicsMapper.addDefense(defenseTopics);

            System.out.println("插入 defense_topics 结果：" + result);
            System.out.println("生成的 topicId: " + defenseTopics.getTopicId());

            // 2. 如果有问题，批量添加
            if (editDefenseDto.getQuestions() != null && !editDefenseDto.getQuestions().isEmpty()) {
                System.out.println("开始添加 " + editDefenseDto.getQuestions().size() + " 个问题");

                for (EditDefenseDto.DefenseQuestionItem item : editDefenseDto.getQuestions()) {
                    DefenseQuestions question = new DefenseQuestions();
                    question.setTopicId(String.valueOf(defenseTopics.getTopicId()));
                    question.setTeacherId(String.valueOf(editDefenseDto.getTeacherId()));
                    question.setQuestionType(item.getQuestionType());
                    question.setQuestion(item.getQuestion());
                    question.setStandardAnswer(item.getStandardAnswer());
                    question.setCreatedAt(LocalDateTime.now());
                    question.setUpdatedAt(LocalDateTime.now());

                    defenseTopicsMapper.addDefenseQuestion(question);
                    System.out.println("添加问题：" + item.getQuestion());
                }
            } else {
                System.out.println("没有问题数据，跳过");
            }

            return Result.success("添加成功");

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("添加失败：" + e.getMessage());
            return Result.error("添加失败：" + e.getMessage());
        }
    }

    @Override
    public Result<PageResult<DefenseTopics>> getAllDefense(int pageNum, int pageSize) {
        // 参数校验
        if (pageNum <= 0) {
            pageNum = 1;
        }
        if (pageSize <= 0) {
            pageSize = 10;
        }

        // 计算偏移量
        int offset = (pageNum - 1) * pageSize;

        // 查询数据
        List<DefenseTopics> list = defenseTopicsMapper.selectAllDefense(offset, pageSize);
        long total = defenseTopicsMapper.countAllDefense();

        // 构建分页结果
        PageResult<DefenseTopics> pageResult = new PageResult<>(list, total, pageNum, pageSize);

        return Result.success(pageResult);
    }



    //TODO 当前的 editDefense 方法采用了"先删除所有问题，再重新添加"的策略，这样会导致已回答的历史记录丢失。
    @Override
    @Transactional
    public Result<Object> editDefense(EditDefenseDto editDefenseDto) {
        try {
            editDefenseDto.setUpdatedAt(LocalDateTime.now());

            // 1. 修改 defense_topics 表
            DefenseTopics defenseTopics = new DefenseTopics();
            defenseTopics.setTopicId(editDefenseDto.getTopicId());
            defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
            defenseTopics.setTopicName(editDefenseDto.getTopicName());
            defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());
            defenseTopics.setDefenseTime(String.valueOf(editDefenseDto.getDefenseTimeAsLocalDateTime()));
            defenseTopics.setUpdatedAt(LocalDateTime.now());

            defenseTopicsMapper.editDefense(defenseTopics);

            // 2. 删除该主题下的所有问题（先清空再重新添加）
            defenseTopicsMapper.deleteQuestionsByTopicId(editDefenseDto.getTopicId());

            // 3. 批量添加新问题
            if (editDefenseDto.getQuestions() != null && !editDefenseDto.getQuestions().isEmpty()) {
                for (EditDefenseDto.DefenseQuestionItem item : editDefenseDto.getQuestions()) {
                    DefenseQuestions question = new DefenseQuestions();
                    question.setTopicId(String.valueOf(editDefenseDto.getTopicId()));
                    question.setTeacherId(String.valueOf(editDefenseDto.getTeacherId()));
                    question.setQuestionType(item.getQuestionType());
                    question.setQuestion(item.getQuestion());
                    question.setStandardAnswer(item.getStandardAnswer());
                    question.setCreatedAt(LocalDateTime.now());
                    question.setUpdatedAt(LocalDateTime.now());

                    defenseTopicsMapper.addDefenseQuestion(question);
                }
            }

            return Result.success("修改成功");

        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("操作失败：" + e.getMessage());
        }
    }

    @Override
    public Result<Object> getDefenseQuestionById(Integer topicId) {
        List<DefenseQuestions> list = defenseTopicsMapper.getDefenseQuestionById(topicId);
        return Result.success(list);
    }

    @Override
    public Result<Object> deleteDefenseTopics(Integer topicId) {
        int result = defenseTopicsMapper.deleteDefenseTopics(topicId);
        return result > 0 ?
                Result.success("删除成功") :
                Result.error("删除失败");
    }

}
