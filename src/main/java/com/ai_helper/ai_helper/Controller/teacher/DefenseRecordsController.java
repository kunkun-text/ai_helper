package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
    答辩记录展示
*/
@RestController
@Slf4j
@RequestMapping("/teacher/defense")
public class DefenseRecordsController {

    @Resource
    private DefenseRecordsService defenseRecordsService;


    /**
     * 获取答辩记录
     * @param pageNum
     * @param pageSize
     * @return
     */
    @GetMapping("/records")
    public Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        return defenseRecordsService.getDefenseRecords(pageNum, pageSize);
    }

    /*
    * 获取对应答辩记录详情
     */
    @GetMapping("/DetailRecords/{defenseId}")
    public Result<DetailRecordsVo> getDefenseDetailRecords(@PathVariable Integer defenseId) {
        return defenseRecordsService.getDefenseDetailRecords(defenseId);
    }

}
