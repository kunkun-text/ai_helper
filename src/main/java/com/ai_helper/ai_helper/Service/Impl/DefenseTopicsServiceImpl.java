package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.beans.BeanUtils;
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
    public Result<Object> addDefense(DefenseTopics defenseTopics) {

        defenseTopics.setCreatedAt(LocalDateTime.now());
        defenseTopics.setUpdatedAt(LocalDateTime.now());

        if (defenseTopics.getDefenseTime() == null ) {
            return Result.error(" 答辩时间不能为空");
        }

        return defenseTopicsMapper.addDefense(defenseTopics) > 0 ?
                Result.success("添加成功") :
                Result.error("添加失败");

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


    @Transactional
    @Override
    public Result<Object> editDefense(EditDefenseDto editDefenseDto) {

        editDefenseDto.setUpdatedAt(LocalDateTime.now());
        editDefenseDto.setCreatedAt(LocalDateTime.now());

        //1 修改defense表
        DefenseTopics defenseTopics = new DefenseTopics();

        BeanUtils.copyProperties(editDefenseDto, defenseTopics);

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
    }

    @Override
    public Result<Object> getDefenseQuestionById(Integer topicId) {
        List<DefenseQuestions> list = defenseTopicsMapper.getDefenseQuestionById(topicId);
        return Result.success(list);
    }

}
