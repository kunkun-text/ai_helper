package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
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
 * 答辩记录展示（教师端）。
 *
 * <p>【N19 · 2026-09-28】整类要求 teacher 角色：此前查全站记录/详情无任何角色校验，
 * 学生登录后可查看他人答辩记录。</p>
 */
@RestController
@Slf4j
@RequestMapping("/teacher/defense")
@RequireRole(UserRole.TEACHER)
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
    * 逐轮五维评分明细（2026-10-01 记录详情增强）
    */
    @GetMapping("/scoreDetail/{defenseId}")
    public Result<List<DefenseScoreRecord>> getScoreDetail(@PathVariable Integer defenseId) {
        return defenseRecordsService.getScoreDetail(defenseId);
    }

    /*
    * 答辩记录搜索功能
     */
    @PostMapping("/search")
    public Result<PageInfo<DefenseRecordsVo>> searchDefenseRecords (@RequestBody DefenseRecordsDto defenseRecordsDto) {
        return defenseRecordsService.selectDefenseRecords(defenseRecordsDto);
    }

}
