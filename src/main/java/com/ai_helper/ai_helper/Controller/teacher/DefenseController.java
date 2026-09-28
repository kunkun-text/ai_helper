package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 教师端课题管理。
 *
 * <p>【N19 · 2026-09-28】整类要求 teacher 角色：此前无任何校验，
 * 学生只要登录（甚至不登录，接口原先不在保护范围内）就能增删改任意课题。</p>
 */
@RestController
@RequestMapping("/teacher")
@RequireRole(UserRole.TEACHER)
public class DefenseController {

    @Resource
    private DefenseTopicsService defenseTopicsService;

    @PostMapping("/addDefense")
    public Result<Object> addDefense(@RequestBody EditDefenseDto editDefenseDto) {
        return defenseTopicsService.addDefense(editDefenseDto);
    }

    @RequestMapping("/getAllDefense")
    public Result<PageResult<DefenseTopics>> getAllDefense(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return defenseTopicsService.getAllDefense(pageNum, pageSize);
    }

    @PostMapping("/editDefense")
    public Result<Object> editDefense(@RequestBody EditDefenseDto editDefenseDto) {
        return defenseTopicsService.editDefense(editDefenseDto);
    }

    @GetMapping("/getDefenseQuestionById")
    public Result<List<DefenseQuestions>> getDefenseQuestionById(@RequestParam Integer topicId) {
        return defenseTopicsService.getDefenseQuestionById(topicId);
    }

    @DeleteMapping("/deleteDefenseTopics")
    public Result<Object> deleteDefenseTopics(@RequestParam Integer topicId) {
        return defenseTopicsService.deleteDefenseTopics(topicId);
    }


}
