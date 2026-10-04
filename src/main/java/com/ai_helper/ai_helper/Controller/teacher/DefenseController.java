package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.mapper.RegisterMapper;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
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

    @Resource
    private RegisterMapper registerMapper;

    @Resource
    private DefenseTopicsMapper defenseTopicsMapper;

    /**
     * 【A3 · 2026-10-05】用登录态解析当前教师的 user_id，写入 DTO；
     * 前端传来的 teacherId 一律忽略（此前前端写死 teacherId: 20，任何教师登录都会把课题挂到 20 号教师名下）。
     *
     * @return null = 解析失败（已给出错误 Result 的场景由调用方处理）
     */
    private Integer resolveCurrentTeacherId(HttpServletRequest request, EditDefenseDto dto) {
        String userNumber = AuthInterceptor.currentUserNumber(request);
        if (userNumber == null || userNumber.isEmpty()) {
            return null;
        }
        UserDto teacher = registerMapper.selectByUserNumber(userNumber);
        if (teacher == null || teacher.getId() == null || teacher.getId().trim().isEmpty()) {
            return null;
        }
        try {
            // login.xml 里 user_id 别名为 id（String），teacher_id 为整型
            return Integer.parseInt(teacher.getId().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PostMapping("/addDefense")
    public Result<Object> addDefense(@RequestBody EditDefenseDto editDefenseDto, HttpServletRequest request) {
        Integer teacherId = resolveCurrentTeacherId(request, editDefenseDto);
        if (teacherId == null) {
            return Result.error("登录教师身份解析失败，请重新登录");
        }
        editDefenseDto.setTeacherId(teacherId);
        return defenseTopicsService.addDefense(editDefenseDto);
    }

    @RequestMapping("/getAllDefense")
    public Result<PageResult<DefenseTopics>> getAllDefense(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return defenseTopicsService.getAllDefense(pageNum, pageSize);
    }

    @PostMapping("/editDefense")
    public Result<Object> editDefense(@RequestBody EditDefenseDto editDefenseDto, HttpServletRequest request) {
        Integer teacherId = resolveCurrentTeacherId(request, editDefenseDto);
        if (teacherId == null) {
            return Result.error("登录教师身份解析失败，请重新登录");
        }
        // 【A3】归属校验：只能编辑当前教师自己的课题
        if (editDefenseDto.getTopicId() == null) {
            return Result.error("课题ID不能为空");
        }
        Integer ownerTeacherId = defenseTopicsMapper.selectTeacherIdByTopicId(editDefenseDto.getTopicId());
        if (ownerTeacherId == null) {
            return Result.error("未找到该课题");
        }
        if (!ownerTeacherId.equals(teacherId)) {
            return Result.error("无权编辑他人的课题");
        }
        editDefenseDto.setTeacherId(teacherId);
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
