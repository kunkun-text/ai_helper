package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.ComponentStatusService;
import com.ai_helper.ai_helper.Service.OcrService;
import com.ai_helper.ai_helper.pojo.vo.ComponentStatusVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 组件自检：汇总 whisper 与 OCR 的安装状态（2026-10-20）。
 *
 * <p>用户要的效果是「有就显示已安装，没有就自动下载」。自动下载分别由
 * {@code WhisperAsrServiceImpl} 与 {@code OcrServiceImpl} 在应用就绪后触发；
 * 本类只负责**把结果说清楚**——启动日志给一段摘要，接口给结构化状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentStatusServiceImpl implements ComponentStatusService {

    /** 等自动安装线程先完成检测（探测 Windows OCR 约 1 秒） */
    private static final long STARTUP_DELAY_MILLIS = 3000L;

    private final VoiceComponentInstaller voiceComponentInstaller;

    private final OcrService ocrService;

    /**
     * 启动 3 秒后打印摘要：此时各组件已完成「有没有」的判断，
     * 缺失的已进入后台下载，摘要会显示「未安装，后台自动下载中」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            logStartupSummary();
        }, "component-status-log");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public List<ComponentStatusVo> all() {
        return List.of(voice(), ocrService.status());
    }

    @Override
    public void logStartupSummary() {
        log.info("========== 本机组件自检 ==========");
        for (ComponentStatusVo status : all()) {
            log.info("{}：{}｜{}", status.name(),
                    status.installed() ? "已安装" : (status.installing() ? "安装中" : "未安装"),
                    status.message());
            log.info("    位置：{}", status.location());
            if (status.installing()) {
                log.info("    进度：{}% {}", status.percent(), status.stage());
            }
        }
        log.info("缺失组件会在后台自动补齐；状态接口：GET /api/system/components");
        log.info("================================");
    }

    /**
     * 语音组件：引擎 + 模型都齐才算已安装（ffmpeg 只影响开发者工具录的 webm）。
     */
    private ComponentStatusVo voice() {
        Path dir = voiceComponentInstaller.installDirectory();
        boolean engine = voiceComponentInstaller.engineReady();
        boolean model = voiceComponentInstaller.modelReady();
        boolean ffmpeg = voiceComponentInstaller.ffmpegReady();
        boolean installed = engine && model;

        String message;
        if (installed) {
            message = "引擎与模型已就绪" + (ffmpeg ? "，ffmpeg 已就绪" : "，ffmpeg 缺失（仅影响 webm 转码）");
        } else if (voiceComponentInstaller.isRunning()) {
            message = "未安装，后台自动下载中（约 550MB）";
        } else if (!voiceComponentInstaller.lastError().isBlank()) {
            message = "未安装：" + voiceComponentInstaller.lastError();
        } else {
            message = "未安装，启动后自动下载（约 550MB）";
        }

        return new ComponentStatusVo("whisper", "语音识别（whisper）", installed,
                voiceComponentInstaller.isRunning(),
                installed ? 100 : voiceComponentInstaller.percent(),
                voiceComponentInstaller.stage(),
                dir.toString(), message);
    }
}
