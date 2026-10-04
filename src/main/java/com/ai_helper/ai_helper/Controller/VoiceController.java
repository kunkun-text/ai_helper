package com.ai_helper.ai_helper.Controller;

import com.ai_helper.ai_helper.Service.AsrService;
import com.ai_helper.ai_helper.Service.TtsService;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 语音答辩支撑接口（F10）。
 *
 * <p>背景：微信「同声传译」插件已确认**个人主体小程序无法使用**（插件管理与服务市场均搜不到），
 * 因此语音答辩改为「后端合成语音播报 + 小程序原生录音上传识别」，不依赖任何小程序插件。</p>
 *
 * <p><b>鉴权（A1 · 2026-10-05）</b>：{@code /api/voice/**} 已纳入
 * {@code WebConfig.PROTECTED_PATHS}，所有接口只服务登录用户——语音答辩与文字答辩
 * 同属答辩链路，不允许匿名调用。token 过期由前端统一处理（提示重新登录）。</p>
 *
 * <p><b>资源保护（D1 · 2026-10-05）</b>：识别前先在 Controller 层检查音频大小，
 * 超限直接拒绝、不会读入内存；whisper 是 CPU 密集任务，用信号量限制并发为 1，
 * 排队超时提示「稍后重试」（{@code WhisperAsrServiceImpl} 内保留二次大小校验）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    /** 上传音频允许的扩展名（录音来自小程序，正常只会是这几种） */
    private static final String[] ALLOWED_AUDIO_EXTS = {"mp3", "wav", "aac", "m4a", "pcm", "amr"};

    /** 【D1】录音文件大小上限（与 WhisperAsrServiceImpl.MAX_AUDIO_BYTES 保持一致，20MB） */
    private static final long MAX_AUDIO_BYTES = 20L * 1024 * 1024;

    /** 【D1】ASR 并发上限：whisper 为 CPU 密集任务，同时只允许 1 个识别任务 */
    private static final Semaphore ASR_PERMITS = new Semaphore(1);

    @Resource
    private TtsService ttsService;

    @Resource
    private AsrService asrService;

    /**
     * 文本转语音：返回可直接播放的音频地址。
     *
     * <p>请求体：{@code {"text": "待播报的题目或总结"}}</p>
     * <p>响应：{@code {"success": true, "url": "/files/tts/xxxx.wav"}}；
     * 合成失败时 {@code success=false, url=""}，前端**静默跳过播报**、不中断答辩。</p>
     */
    @PostMapping("/tts")
    public Result<Map<String, Object>> tts(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("text");
        String text = raw == null ? "" : String.valueOf(raw);

        long start = System.currentTimeMillis();
        String url = ttsService.synthesizeToUrl(text);
        long cost = System.currentTimeMillis() - start;

        if (url.isEmpty()) {
            log.warn("语音合成未成功 - 文本长度: {}, 耗时: {} ms", text.length(), cost);
        } else {
            log.info("语音合成请求完成 - 文本长度: {}, 耗时: {} ms, url: {}", text.length(), cost, url);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("success", !url.isEmpty());
        data.put("url", url);
        return Result.success(data);
    }

    /**
     * 语音识别：接收小程序上传的录音，返回识别文字。
     *
     * <p>表单字段：{@code file}（音频文件）、{@code format}（可选，扩展名，如 mp3）。
     * 响应：{@code {"success": true, "text": "识别出的文字"}}。
     * 识别不出内容时 {@code success=false}，text 为空串 —— 前端提示「没听清，请重录」。</p>
     *
     * <p>引擎未安装/识别超时等情况会抛 {@code BusinessException}，
     * 由 {@code GlobalExceptionHandler} 转成一句用户看得懂的话返回。</p>
     */
    @PostMapping("/asr")
    public Result<Map<String, Object>> asr(@RequestParam(value = "file", required = false) MultipartFile file,
                                           @RequestParam(value = "format", required = false) String format) {
        if (file == null || file.isEmpty()) {
            return Result.error("录音内容为空，请重新录制");
        }

        // 【D1】先检查大小再读内存：超限直接拒绝，不会把超大文件读进 JVM，也不会启动 ffmpeg/whisper
        if (file.getSize() > MAX_AUDIO_BYTES) {
            return Result.error("录音文件过大，请缩短作答时长后重试");
        }

        // 【D1】并发控制：whisper 同时只允许 1 个任务，排队等不到就提示稍后重试
        boolean acquired = false;
        try {
            acquired = ASR_PERMITS.tryAcquire(3, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            log.warn("语音识别并发已满，本次请求被拒绝 - 客户端: {}", file.getOriginalFilename());
            return Result.error("当前识别人数较多，请稍后重试");
        }

        String ext = resolveExt(format, file.getOriginalFilename());
        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传录音失败", e);
            return Result.error("录音上传失败，请重试");
        } catch (OutOfMemoryError oom) {
            log.error("读取上传录音内存不足", oom);
            return Result.error("录音上传失败，请重试");
        }

        long start = System.currentTimeMillis();
        String text;
        try {
            text = asrService.transcribe(audio, ext);
        } finally {
            ASR_PERMITS.release();
        }
        long cost = System.currentTimeMillis() - start;

        log.info("语音识别请求完成 - 音频: {} 字节, 格式: {}, 耗时: {} ms, 识别长度: {}",
                audio.length, ext, cost, text == null ? 0 : text.length());

        Map<String, Object> data = new HashMap<>();
        data.put("success", text != null && !text.trim().isEmpty());
        data.put("text", text == null ? "" : text.trim());
        return Result.success(data);
    }

    /**
     * 语音能力状态：前端进页面时查一次，用于决定录音按钮是否可用，
     * 避免学生按了半天才发现引擎没装。
     *
     * <p>新增 {@code asrInstalling} / {@code asrProgress}：首次启动自动下载语音组件期间，
     * 前端应显示「组件准备中 x%」并在下载完成后自动放行，而不是直接报"不可用"。</p>
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("asrReady", asrService.available());
        data.put("asrMessage", asrService.unavailableReason());
        data.put("asrInstalling", asrService.installing());
        data.put("asrProgress", asrService.installProgress());
        return Result.success(data);
    }

    /** 取音频扩展名：优先前端显式传入，其次从文件名推断；不在白名单内一律按 mp3 处理 */
    private String resolveExt(String format, String originalFilename) {
        String candidate = format;
        if (UploadUtils.isBlank(candidate)) {
            candidate = originalFilename;
        }
        if (UploadUtils.isBlank(candidate)) {
            return "mp3";
        }
        String lower = candidate.trim().toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : lower;
        for (String allowed : ALLOWED_AUDIO_EXTS) {
            if (allowed.equals(ext)) {
                return ext;
            }
        }
        return "mp3";
    }
}
