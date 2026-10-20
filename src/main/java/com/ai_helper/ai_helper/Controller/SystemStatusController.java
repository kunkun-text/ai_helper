package com.ai_helper.ai_helper.Controller;

import com.ai_helper.ai_helper.Service.ComponentStatusService;
import com.ai_helper.ai_helper.pojo.vo.ComponentStatusVo;
import com.ai_helper.ai_helper.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本机组件状态（2026-10-20）。
 *
 * <p>给运维/前端一个「一眼看清装没装」的入口：whisper 与 OCR 各自是否已安装、
 * 正在下载到哪一步、失败原因是什么。接口在登录保护范围内（`/api/system/**`）。</p>
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final ComponentStatusService componentStatusService;

    @GetMapping("/components")
    public Result<List<ComponentStatusVo>> components() {
        return Result.success(componentStatusService.all());
    }
}
