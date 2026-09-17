package com.ai_helper.ai_helper.Controller.student;


import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.UploadUtils;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 学生端答辩记录查询。
 *
 * <p>身份一律取自登录态（AuthInterceptor 写入的 request attribute），
 * 不再接受前端传入的 userNumber —— 原先学生只要把学号改成别人的，
 * 就能看到他人全部答辩记录、报告与视频地址。</p>
 */
@RestController
@RequestMapping("/student")
public class StudentDefenseRecordsController {

    @Resource
    private DefenseRecordsService defenseRecordsService;

    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;

    @GetMapping("/DefenseRecords")
    public Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request
    ) {
        String userNumber = currentUserNumber(request);
        if (UploadUtils.isBlank(userNumber)) {
            return Result.error("登录状态已失效，请重新登录");
        }
        return defenseRecordsService.getStudentDefenseRecords(pageNum, pageSize, userNumber);
    }

    @GetMapping("/DefenseDetailRecords")
    public Result<DetailRecordsVo> getDefenseDetailRecords(
            @RequestParam Integer defenseRecordId,
            HttpServletRequest request
    ) {
        if (!isOwnedByCurrentUser(defenseRecordId, request)) {
            return Result.error("无权查看该答辩记录");
        }
        return defenseRecordsService.getDefenseDetailRecords(defenseRecordId);
    }

    /*
     * 获取答辩详情中答辩问题及回复
     */
    @GetMapping("/questions/{defenseId}")
    public Result<List<QuestionDetailVo>> getDefenseQuestions(@PathVariable Integer defenseId,
                                                              HttpServletRequest request) {
        if (!isOwnedByCurrentUser(defenseId, request)) {
            return Result.error("无权查看该答辩记录");
        }
        return defenseRecordsService.getDefenseQuestionsAnswers(defenseId);
    }

    /*
     * 获取答辩题目（公共数据，不涉及个人信息）
     */
    @GetMapping("/getDefenseTopic")
    public Result<List<DefenseTopics>> getDefenseTopic() {
        return defenseRecordsService.getDefenseTopic();
    }

    /**
     * 归属校验：该答辩记录是否属于当前登录学生。
     */
    private boolean isOwnedByCurrentUser(Integer defenseId, HttpServletRequest request) {
        String userNumber = currentUserNumber(request);
        if (defenseId == null || UploadUtils.isBlank(userNumber)) {
            return false;
        }
        String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber.trim());
        return internalUserId != null
                && defenseRecordsMapper.countOwnedDefenseRecord(defenseId, internalUserId) > 0;
    }

    private String currentUserNumber(HttpServletRequest request) {
        Object value = request.getAttribute("userNumber");
        return value == null ? null : String.valueOf(value);
    }
}
