package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


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

    /*
    * 获取答辩详情中答辩问题及回复
     */
    @GetMapping("/questions/{defenseId}")
    public Result<List<QuestionDetailVo>> getDefenseQuestions(@PathVariable Integer defenseId) {
        return defenseRecordsService.getDefenseQuestionsAnswers(defenseId);
    }

    /*
    * 答辩记录搜索功能
     */
    @PostMapping("/search")
    public Result<PageInfo<DefenseRecordsVo>> searchDefenseRecords (@RequestBody DefenseRecordsDto defenseRecordsDto) {
        return defenseRecordsService.selectDefenseRecords(defenseRecordsDto);
    }

}
