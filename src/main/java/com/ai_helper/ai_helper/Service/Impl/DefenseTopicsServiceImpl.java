package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DefenseTopicsServiceImpl implements DefenseTopicsService {

    @Autowired
    private DefenseTopicsMapper defenseTopicsMapper;

    /**
     * 新增课题 + 题目（多表写入）。
     *
     * <p>【N20 · 2026-09-28】原实现两个问题：① {@code @Transactional} 形同虚设 ——
     * catch 住异常后 return 错误码，异常没抛出去、事务照样提交，插入一半的课题会留在库里；
     * ② 把 {@code e.getMessage()}（可能含 SQL 细节）直接回给前端。
     * 现在不捕获异常：写库失败自然上抛 → 事务回滚 → GlobalExceptionHandler 返回统一文案。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> addDefense(EditDefenseDto editDefenseDto) {
        // 前置校验：还未写库，直接返回错误即可
        // 【B3 · 2026-10-05】解析失败（null）必须拒绝，旧实现会把 null 拼成字符串 "null" 落库
        if (editDefenseDto.getDefenseTimeAsLocalDate() == null) {
            return Result.error("答辩时间不能为空，且格式必须为 yyyy-MM-dd");
        }

        // 1. 添加到 defense_topics 表
        DefenseTopics defenseTopics = new DefenseTopics();
        defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
        defenseTopics.setTopicName(editDefenseDto.getTopicName());
        defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());
        defenseTopics.setDefenseTime(editDefenseDto.getDefenseTimeAsLocalDateTime().toString());
        defenseTopics.setCreatedAt(LocalDateTime.now());
        defenseTopics.setUpdatedAt(LocalDateTime.now());

        defenseTopicsMapper.addDefense(defenseTopics);
        log.info("新增答辩课题 - topicId: {}, 名称: {}", defenseTopics.getTopicId(), defenseTopics.getTopicName());

        // 2. 批量添加题目（与课题同在一个事务：中途失败整体回滚，不留半个课题）
        if (editDefenseDto.getQuestions() != null && !editDefenseDto.getQuestions().isEmpty()) {
            for (EditDefenseDto.DefenseQuestionItem item : editDefenseDto.getQuestions()) {
                defenseTopicsMapper.addDefenseQuestion(buildQuestion(item, editDefenseDto, defenseTopics.getTopicId()));
            }
            log.info("新增答辩题目 {} 道 - topicId: {}",
                    editDefenseDto.getQuestions().size(), defenseTopics.getTopicId());
        }

        return Result.success("添加成功");
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
    /**
     * 修改课题 + 重建题目。
     *
     * <p>【N20 · 2026-09-28】同 addDefense：不再 catch 后 return，异常上抛让事务真正回滚。
     * 本方法内部是「先清空题目、再重建」，一旦中途失败，旧实现会留下「题目被清空」的
     * 半截状态；现在整体回滚，至少数据是可用的旧状态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> editDefense(EditDefenseDto editDefenseDto) {
        // 【B3】日期解析失败必须拒绝，禁止把 "null" 写库
        if (editDefenseDto.getDefenseTimeAsLocalDate() == null) {
            return Result.error("答辩时间不能为空，且格式必须为 yyyy-MM-dd");
        }
        editDefenseDto.setUpdatedAt(LocalDateTime.now());

        // 1. 修改 defense_topics 表
        DefenseTopics defenseTopics = new DefenseTopics();
        defenseTopics.setTopicId(editDefenseDto.getTopicId());
        defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
        defenseTopics.setTopicName(editDefenseDto.getTopicName());
        defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());
        defenseTopics.setDefenseTime(editDefenseDto.getDefenseTimeAsLocalDateTime().toString());
        defenseTopics.setUpdatedAt(LocalDateTime.now());

        defenseTopicsMapper.editDefense(defenseTopics);

        // 2. 删除该主题下的所有问题（先清空再重新添加）
        defenseTopicsMapper.deleteQuestionsByTopicId(editDefenseDto.getTopicId());

        // 3. 批量添加新问题
        if (editDefenseDto.getQuestions() != null && !editDefenseDto.getQuestions().isEmpty()) {
            for (EditDefenseDto.DefenseQuestionItem item : editDefenseDto.getQuestions()) {
                defenseTopicsMapper.addDefenseQuestion(buildQuestion(item, editDefenseDto, editDefenseDto.getTopicId()));
            }
        }

        log.info("修改答辩课题完成 - topicId: {}", editDefenseDto.getTopicId());
        return Result.success("修改成功");
    }

    @Override
    public Result<List<DefenseQuestions>> getDefenseQuestionById(Integer topicId) {
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

    @Override
    public Result<Object> getTopicById(Integer topicId) {
        try {
            TopicDto topic = defenseTopicsMapper.getTopicById(topicId);
            if (topic != null) {
                return Result.success(topic);
            } else {
                return Result.error("未找到该答辩主题");
            }
        } catch (Exception e) {
            // 【N31】不再 e.printStackTrace() + 把 SQL 细节回传前端
            log.error("查询答辩主题失败 - topicId: {}", topicId, e);
            return Result.error("查询失败，请稍后重试");
        }
    }

    /** 组装一道题目（新增与修改两处复用，避免字段遗漏） */
    private DefenseQuestions buildQuestion(EditDefenseDto.DefenseQuestionItem item,
                                           EditDefenseDto editDefenseDto, Integer topicId) {
        DefenseQuestions question = new DefenseQuestions();
        question.setTopicId(topicId);
        question.setTeacherId(editDefenseDto.getTeacherId());
        question.setQuestionType(item.getQuestionType());
        question.setQuestion(item.getQuestion());
        question.setStandardAnswer(item.getStandardAnswer());
        question.setCreatedAt(LocalDateTime.now());
        question.setUpdatedAt(LocalDateTime.now());
        return question;
    }
}
