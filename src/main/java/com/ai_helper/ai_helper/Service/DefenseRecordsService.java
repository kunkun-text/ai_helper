package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;

public interface DefenseRecordsService {

    /**
     * 获取 defense_history 表中的数据
     *
     * @return defense_history 表中的数据
     */
    Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(Integer pageNum, Integer pageSize);

    Result<DetailRecordsVo> getDefenseDetailRecords(Integer defenseId);
}
