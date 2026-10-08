package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.AuditLogService;
import com.ai_helper.ai_helper.Service.VoiceArchiveService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAuditLog;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import com.ai_helper.ai_helper.pojo.entity.VoiceResponse;
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

    @Resource
    private VoiceArchiveService voiceArchiveService;

    @Resource
    private AuditLogService auditLogService;


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

    /**
     * 语音答辩逐轮录音回放（F11 · 2026-10-08）。
     *
     * <p>整类已有 {@code @RequireRole(TEACHER)}，教师可查看任意场次的录音归档，
     * 无需再按归属过滤（与教师端查看他人答辩记录同一口径）。</p>
     */
    @GetMapping("/voiceRecords/{defenseId}")
    public Result<List<VoiceResponse>> getVoiceRecords(@PathVariable Integer defenseId) {
        return Result.success(voiceArchiveService.listByDefenseId(defenseId));
    }

    /**
     * 答辩场次审计轨迹（N15 · 2026-10-08）：该场次的开始 / 续答 / 结束事件，时间正序。
     *
     * <p>整类已有 {@code @RequireRole(TEACHER)}；用于事后复盘轮次异常、被续答、结束总分等问题。
     * 注意：只提供接口，小程序端暂未做可视化入口（见 CHANGES 遗留清单）。</p>
     */
    @GetMapping("/auditLog/{defenseId}")
    public Result<List<DefenseAuditLog>> getAuditLog(@PathVariable Integer defenseId) {
        return Result.success(auditLogService.listByDefenseId(defenseId));
    }

    /*
    * 答辩记录搜索功能
    */
    @PostMapping("/search")
    public Result<PageInfo<DefenseRecordsVo>> searchDefenseRecords (@RequestBody DefenseRecordsDto defenseRecordsDto) {
        return defenseRecordsService.selectDefenseRecords(defenseRecordsDto);
    }

}
