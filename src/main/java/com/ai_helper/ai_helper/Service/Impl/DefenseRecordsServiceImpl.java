package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefenseRecordsServiceImpl implements DefenseRecordsService {

    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;


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

        // 设置默认值，避免空指针异常
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


}
