package com.ai_helper.ai_helper.Controller.student;


import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/student")
public class StudentDefenseRecordsController {

    @Resource
    private DefenseRecordsService defenseRecordsService;

    @GetMapping("/DefenseRecords")
    public Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam String userNumber
    ) {
        return defenseRecordsService.getStudentDefenseRecords(pageNum, pageSize, userNumber);
    }

    @GetMapping("/DefenseDetailRecords")
    public Result<DetailRecordsVo> getDefenseDetailRecords(
            @RequestParam Integer defenseRecordId
    ) {
        return defenseRecordsService.getDefenseDetailRecords(defenseRecordId);
    }

    /*
     * 获取答辩详情中答辩问题及回复
     */
    @GetMapping("/questions/{defenseId}")
    public Result<List<QuestionDetailVo>> getDefenseQuestions(@PathVariable Integer defenseId) {
        return defenseRecordsService.getDefenseQuestionsAnswers(defenseId);
    }

    /*
    * 获取答辩题目
     */
    @GetMapping("/getDefenseTopic")
    public Result<List<DefenseTopics>> getDefenseTopic() {
        return defenseRecordsService.getDefenseTopic();
    }



}
