package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/teacher")
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
