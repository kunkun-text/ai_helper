package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
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
        List<DefenseRecordsVo> records = defenseRecordsMapper.getDefenseRecords();
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
}
