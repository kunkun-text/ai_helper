package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.AsrService;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.constant.VoiceConstants;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.util.AsrTextCorrector;
import com.ai_helper.ai_helper.util.UploadUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 <b>whisper.cpp</b> 的本地语音识别（离线，不联网、不依赖小程序插件）。
 *
 * <p>调用方式：把上传的音频落成临时文件，然后起一个进程执行
 * {@code whisper-cli.exe -m <模型> -f <音频> -l zh -otxt -of <输出前缀> -np}，
 * 读取生成的 {@code .txt} 即识别结果。</p>
 *
 * <p><b>部署提示</b>：引擎（whisper-cli.exe）与模型（ggml-*.bin）都是本机文件。
 * 未安装时应用**照常启动**，只是语音作答不可用 —— {@link #available()} 为 false，
 * 接口会返回一句明确的提示，不会让前端干等或白屏。
 * 可用 {@code scripts/install-whisper.ps1} 一键下载安装。</p>
 *
 * <p><b>安全</b>：命令行参数全部由后端拼装（引擎路径、模型路径、临时文件路径），
 * 文件名由程序生成、扩展名走白名单校验，用户上传的内容不参与命令行构造。</p>
 */
@Slf4j
@Service
public class WhisperAsrServiceImpl implements AsrService {

    /** 单次上传音频大小上限（20MB ≈ 十几分钟 mp3），防御性限制 */
    private static final long MAX_AUDIO_BYTES = 20L * 1024 * 1024;

    /** ffmpeg 转码超时（秒） */
    private static final long FFMPEG_TIMEOUT_SECONDS = 120L;

    @Resource
    private AppProperties appProperties;

    @Resource
    private FileStorageService fileStorageService;

    /** 语音组件自动下载器（引擎/模型/ffmpeg 缺失时启动后自动补齐） */
    @Resource
    private VoiceComponentInstaller voiceComponentInstaller;

    /** 本次启动是否需要自动补齐语音组件（@PostConstruct 时判定缺失且开启了 auto-install） */
    private volatile boolean bootstrapNeeded = false;

    /** 解析好的引擎路径（null = 未找到） */
    private volatile String exePath;

    /** 解析好的模型路径（null = 未找到） */
    private volatile String modelPath;

    /** 解析好的 ffmpeg 路径（null = 未找到，退回 Java 解码，此时只支持 wav/mp3） */
    private volatile String ffmpegPath;

    /** 解析好的 VAD 模型路径（null = 未找到，自动跳过 VAD，不影响识别） */
    private volatile String vadModelPath;

    /** JSON 结果解析器（whisper.cpp 的 -ojf 输出） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 【2026-10-28】initial prompt 的硬上限（字符数）。
     *
     * <p><b>实测事故</b>：{@code app.voice.prompt} 默认那段中文术语表约 600 字符，
     * 传给 whisper-cli 后对方**直接崩溃**（EXIT=0xC0000409 stack buffer overrun，
     * 日志只留下一句 {@code failed to process audio}）。表现是**语音答辩 60 秒录音识别 100% 失败**，
     * 而视频转写因为用的是 180 字以内的短 prompt 所以正常。
     * 600 字符必崩、4 字符正常，故这里统一截到 120 字并记日志，不依赖配置写得对不对。</p>
     */
    private static final int MAX_PROMPT_CHARS = 120;

    /** 转写后处理纠错词表（错误写法 → 正确写法），空表表示不纠错 */
    private volatile Map<String, String> correctionRules = Map.of();

    /** 不可用原因（空串 = 可用） */
    private volatile String unavailableReason = "语音识别尚未初始化";

    @PostConstruct
    public void init() {
        if (refresh()) {
            return;
        }
        // 组件缺失：开启自动安装则由「启动就绪事件」在后台补齐（不阻塞启动，也不影响文字答辩）
        if (appProperties.getVoice().isAutoInstall()) {
            bootstrapNeeded = true;
            unavailableReason = "语音组件正在首次准备，请稍候";
            log.info("语音组件缺失，已开启自动安装（app.voice.auto-install=true）："
                    + "将在启动完成后于后台自动下载引擎/模型/ffmpeg，详见后续日志");
        } else {
            log.warn("语音识别未就绪：{}。学生语音作答将不可用；"
                    + "可手动运行 scripts/install-whisper.ps1 安装，"
                    + "或在 application.yml 开启 app.voice.auto-install", unavailableReason);
        }
    }

    /**
     * 应用完全启动后再触发下载：避免几百 MB 的下载拖住 Spring 启动（接口先可用）。
     *
     * <p>下载在 daemon 后台线程中进行，完成后自动 {@link #refresh()} 让语音立即可用；
     * 下载期间 {@link #installing()} 为 true，前端据此提示"组件准备中"。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!bootstrapNeeded) {
            return;
        }
        if (voiceComponentInstaller.isRunning()) {
            return;
        }
        Thread worker = new Thread(() -> {
            log.info("开始自动安装语音组件（首次启动需要下载，耗时取决于网速；"
                    + "期间语音作答不可用，文字答辩不受影响）");
            boolean ok = voiceComponentInstaller.install();
            refresh();
            if (ok) {
                log.info("语音组件自动安装完成 - 引擎: {}, 模型: {}, 音频转码: {}",
                        exePath, modelPath,
                        ffmpegPath == null ? "未找到 ffmpeg（仅支持 wav/mp3）" : ffmpegPath);
            } else {
                log.warn("语音组件自动安装未成功：{}（可手动运行 scripts/install-whisper.ps1）",
                        unavailableReason);
            }
        }, "voice-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 重新解析引擎 / 模型 / ffmpeg 路径，并刷新就绪状态。
     *
     * <p>启动时与自动下载完成后各调一次 —— 这样"下载完自动可用"无需重启。</p>
     *
     * @return true 表示引擎与模型都已就绪（ffmpeg 为可选增强，不影响返回值）
     */
    private boolean refresh() {
        this.exePath = resolveExe();
        if (exePath == null) {
            unavailableReason = "未找到语音识别引擎（whisper-cli.exe / main.exe）";
            return false;
        }

        this.modelPath = resolveModel();
        if (modelPath == null) {
            unavailableReason = "未找到语音识别模型（ggml-*.bin）";
            return false;
        }

        unavailableReason = "";

        // 转写纠错词表（外置，可编辑）；加载失败只降级为不纠错
        this.correctionRules = loadCorrections();

        // VAD（语音活动检测）模型可选：缺失只降级为「不启用 VAD」，不影响识别
        this.vadModelPath = resolveVadModel();
        if (appProperties.getVoice().isVadEnabled()) {
            if (vadModelPath == null) {
                log.warn("已启用 VAD（app.voice.vad-enabled=true），但未找到 VAD 模型——"
                        + "将跳过语音活动检测（静音/鼠标声可能被转成文字）。"
                        + "可把 ggml-silero-v5.1.2.bin 放到 {}",
                        Path.of(exePath).getParent());
            } else {
                log.info("VAD 已启用 - 模型: {}", vadModelPath);
            }
        }

        // ffmpeg 可选但强烈建议：开发者工具（模拟器）录出的是 webm，只有它能解码
        this.ffmpegPath = resolveFfmpeg();
        if (ffmpegPath == null) {
            log.warn("未找到 ffmpeg，将只用 Java 解码器（仅支持 wav / mp3）——"
                    + "**开发者工具里录出来的 webm 音频将无法识别**；"
                    + "可在 application.yml 配置 app.voice.ffmpeg-exe");
        }

        log.info("语音识别已就绪 - 引擎: {}, 模型: {}, 语言: {}, 音频转码: {}",
                exePath, modelPath, appProperties.getVoice().getLanguage(),
                ffmpegPath == null ? "未找到 ffmpeg（仅支持 wav/mp3）" : ffmpegPath);
        return true;
    }

    @Override
    public boolean available() {
        return exePath != null && modelPath != null;
    }

    @Override
    public String unavailableReason() {
        // 正在首次自动下载：给出进度，避免前端显示成"不可用"让学生以为坏了
        if (!available() && voiceComponentInstaller.isRunning()) {
            return "语音识别组件首次下载中（" + voiceComponentInstaller.percent() + "%）："
                    + voiceComponentInstaller.stage();
        }
        // 自动安装失败过：保留原始原因并附上手动安装指引
        if (!available() && !voiceComponentInstaller.lastError().isEmpty()) {
            return unavailableReason + "（自动安装失败：" + voiceComponentInstaller.lastError()
                    + "，可手动运行 scripts/install-whisper.ps1）";
        }
        return unavailableReason;
    }

    @Override
    public boolean installing() {
        return !available() && voiceComponentInstaller.isRunning();
    }

    @Override
    public int installProgress() {
        return voiceComponentInstaller.percent();
    }

    @Override
    public String transcribe(byte[] audio, String format) {
        if (!available()) {
            throw new BusinessException("语音识别不可用：" + unavailableReason + "（可先用文字作答）");
        }
        if (audio == null || audio.length == 0) {
            throw new BusinessException("录音内容为空，请重新录制");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new BusinessException("录音文件过大，请缩短作答时长后重试");
        }

        // 排查辅助：记录音频大小与文件头，并保留最近一次上传的原始音频。
        // 背景：小程序在「开发者工具」和「真机」上录出的容器格式可能不同（webm / ogg / mp3 …），
        // 保留现场便于快速定位"识别引擎解不了"的问题。
        // 【N78 · 2026-10-15】落盘保留现场改为配置开关（app.voice.asr-debug-dump-enabled，默认 false）：
        // 原实现无条件覆写 ai-helper-asr-last.*，最后一名学生的录音会长期残留在磁盘上。
        String declaredExt = normalizeExt(format);
        String sniffedFormat = sniffFormat(audio);
        log.info("收到录音 - 声明格式: {}, 大小: {} 字节, 文件头: {}, 嗅探格式: {}",
                declaredExt, audio.length, headHex(audio), sniffedFormat);
        if (appProperties.getVoice().isAsrDebugDumpEnabled()) {
            try {
                Files.write(Path.of(System.getProperty("java.io.tmpdir"),
                        "ai-helper-asr-last." + declaredExt), audio);
            } catch (Exception ignored) {
                // 仅排查用途，失败不影响主流程
            }
        }

        String ext = normalizeExt(format);
        Path workDir = tempDir();
        Path audioFile = null;
        Path wavFile = null;
        Path outputPrefix = workDir.resolve("asr-out-" + System.nanoTime());
        Path txtFile = Path.of(outputPrefix + ".txt");
        Path jsonFile = Path.of(outputPrefix + ".json");
        try {
            audioFile = Files.createTempFile(workDir, "asr-", "." + ext);
            Files.write(audioFile, audio);

            // 【2026-09-29 实测修正】统一转成 wav 再识别。
            // 原因：小程序录音只能录 aac / mp3，而 whisper.cpp 解 mp3 不稳定 ——
            // 实测表现为"退出码 0 但不产出结果文件"，学生侧只看到"识别失败"。
            // 服务端解码成 wav 后识别正常；转换失败也不致命，退回原文件交给引擎。
            Path inputForEngine = audioFile;
            // 以「嗅探出的真实格式」为准：前端声明的可能不准（模拟器与真机产出的容器不同）
            if (!"wav".equals(sniffedFormat)) {
                // 优先级：ffmpeg（webm / mp3 / aac 通吃）→ Java 解码（仅 wav / mp3）
                wavFile = convertWithFfmpeg(audioFile, workDir);
                if (wavFile == null) {
                    wavFile = decodeToWav(audioFile, workDir);
                }
                if (wavFile != null) {
                    inputForEngine = wavFile;
                } else {
                    log.warn("音频未能转码（声明格式: {}, 实际嗅探: {}），改为直接把原文件交给识别引擎",
                            declaredExt, sniffedFormat);
                }
            } else {
                log.info("录音已是 wav（{} 字节），直接交给识别引擎", audio.length);
            }

            return runWhisper(inputForEngine, outputPrefix,
                    appProperties.getVoice().getTimeoutSeconds(), null, null).text();
        } catch (IOException e) {
            // 【F2】IO 细节（临时文件路径等）只记日志，前端给固定文案
            log.error("语音识别 IO 异常", e);
            throw new BusinessException("语音识别失败，请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("语音识别被中断，请重试");
        } finally {
            deleteQuietly(audioFile);
            deleteQuietly(wavFile);
            deleteQuietly(txtFile);
            deleteQuietly(jsonFile);
        }
    }

    @Override
    public String transcribeMediaFile(Path mediaFile, long timeoutSeconds) {
        return transcribeMediaFile(mediaFile, timeoutSeconds, null);
    }

    @Override
    public String transcribeMediaFile(Path mediaFile, long timeoutSeconds, String initialPrompt) {
        if (!available()) {
            throw new BusinessException("语音识别不可用：" + unavailableReason);
        }
        if (mediaFile == null || !Files.isRegularFile(mediaFile)) {
            throw new BusinessException("视频文件不存在或不可读");
        }
        if (ffmpegPath == null) {
            throw new BusinessException("未找到 ffmpeg，无法从视频中提取音轨（可在 app.voice.ffmpeg-exe 指定路径）");
        }

        Path workDir = tempDir();
        Path wav = null;
        Path outputPrefix = workDir.resolve("asr-video-" + System.nanoTime());
        Path txtFile = Path.of(outputPrefix + ".txt");
        Path jsonFile = Path.of(outputPrefix + ".json");
        long start = System.currentTimeMillis();
        try {
            // 只抽音轨：-vn 丢掉画面。用户明确视频是答辩现场回答，只需听声识别，
            // 不做压缩、不做抽帧/画面分析，因此这里不产生任何视频中间文件。
            // ffmpeg 单独限时（默认 600s），不要把整场 1800s 预算全给它；
            // whisper 用剩余预算，避免"抽音轨超时 + 转写超时"叠加到 3600s。
            long ffmpegTimeout = Math.min(timeoutSeconds,
                    Math.max(30L, appProperties.getVideoAnalysis().getFfmpegTimeoutSeconds()));
            wav = extractAudioTrack(mediaFile, workDir, ffmpegTimeout,
                    appProperties.getVideoAnalysis().getAudioFilter());
            long elapsedSeconds = (System.currentTimeMillis() - start) / 1000L;
            long asrTimeout = Math.max(60L, timeoutSeconds - elapsedSeconds);
            log.info("视频音轨已提取 - 源文件: {}, wav: {} 字节, 耗时: {} ms, 转写预算: {} s",
                    mediaFile.getFileName(), Files.size(wav), System.currentTimeMillis() - start,
                    asrTimeout);

            // 视频可用独立模型档位：离线任务可以慢一点换准确率，60 秒语音仍用默认 small
            String videoModel = resolveModelForTier(
                    appProperties.getVideoAnalysis().getAsrModel());
            AsrOutcome outcome = runWhisper(wav, outputPrefix, asrTimeout, initialPrompt, videoModel);
            enforceSpeechGate(outcome, estimateWavDurationMs(wav),
                    appProperties.getVideoAnalysis());
            return outcome.text();
        } catch (IOException e) {
            log.error("视频转写 IO 异常", e);
            throw new BusinessException("视频语音识别失败，请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("视频语音识别被中断，请重试");
        } finally {
            deleteQuietly(wav);
            deleteQuietly(txtFile);
            deleteQuietly(jsonFile);
        }
    }

    /**
     * 有效语音闸门：噪声/静音占主导的素材不进入评分。
     *
     * <p>2026-10-20 实测：2 分钟录屏里静音 78.6 秒、平均音量 -31dB，
     * 旧实现仍会转出 211 字并打出 8/50。这里用 whisper JSON 的分段时长
     * 估算「有效语音」，低于阈值时直接判素材不足；JSON 不可用时跳过闸门，
     * 不让质量检测本身成为新的失败点。</p>
     */
    private void enforceSpeechGate(AsrOutcome outcome, long audioMs,
                                   AppProperties.VideoAnalysis cfg) {
        if (outcome == null || !outcome.metricsAvailable() || audioMs <= 0) {
            return;
        }
        // token 时间戳偶尔会重叠，按音频总长封顶，避免比例超过 100%
        long effectiveSpeechMs = Math.min(outcome.speechMs(), audioMs);
        double speechSeconds = effectiveSpeechMs / 1000.0;
        double ratio = (double) effectiveSpeechMs / audioMs;
        log.info("ASR 语音质量 - 有效语音: {} s / 音频: {} s（{}%），分段: {}, "
                        + "平均置信度: {}, 低置信分段: {}",
                String.format(Locale.ROOT, "%.1f", speechSeconds),
                String.format(Locale.ROOT, "%.1f", audioMs / 1000.0),
                String.format(Locale.ROOT, "%.1f", ratio * 100),
                outcome.segmentCount(),
                String.format(Locale.ROOT, "%.3f", outcome.avgConfidence()),
                outcome.lowConfidenceSegments());
        if (speechSeconds < cfg.getMinSpeechSeconds() || ratio < cfg.getMinSpeechRatio()) {
            throw new BusinessException(String.format(Locale.ROOT,
                    "视频里有效说话内容过少（约 %.0f 秒，占 %.0f%%），无法评分。"
                            + "请确认视频有清晰人声后再试。",
                    speechSeconds, ratio * 100));
        }
    }

    /** 估算 16kHz / 单声道 / 16bit wav 的时长（毫秒） */
    private long estimateWavDurationMs(Path wav) {
        try {
            long dataBytes = Files.size(wav) - 44L;
            return dataBytes <= 0 ? 0 : dataBytes * 1000L / (16000L * 2L);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 从视频里抽 16kHz 单声道 wav。
     *
     * <p>用文件路径而非字节数组：答辩视频可能几百 MB，读进堆会直接 OOM。
     * 抽音轨是纯流式解复用，10 分钟视频通常几秒内完成。</p>
     */
    private Path extractAudioTrack(Path video, Path workDir, long timeoutSeconds, String audioFilter)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile(workDir, "video-audio-", ".wav");

        // 先带滤镜跑；如果本机 ffmpeg 不支持该滤镜（不同构建差异很大），再退回无滤镜重试一次。
        List<String> filters = new ArrayList<>();
        if (!UploadUtils.isBlank(audioFilter)) {
            filters.add(audioFilter);
        }
        filters.add("");
        String lastOutput = "";
        int lastExit = -1;
        for (String filter : filters) {
            List<String> command = new ArrayList<>(List.of(
                    ffmpegPath, "-y",
                    "-i", video.toAbsolutePath().toString(),
                    "-vn",
                    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le"));
            if (!filter.isBlank()) {
                // 2026-10-20：真实录屏平均音量 -31dB，先高通去低频轰鸣、再做动态响度归一化。
                // 内置 ffmpeg 是精简构建，没有 loudnorm，所以这里用 dynaudnorm。
                command.add("-af");
                command.add(filter);
                log.info("视频音轨预处理滤镜: {}", filter);
            }
            command.add("-loglevel");
            command.add("error");
            command.add(out.toAbsolutePath().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                deleteQuietly(out);
                throw new BusinessException("音轨提取超时（超过 " + timeoutSeconds + " 秒），请稍后重试");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // wav 头 + 空数据约 44 字节：小于等于它说明没有声轨（纯静音或视频无音轨）
            if (process.exitValue() == 0 && Files.exists(out) && Files.size(out) > 44) {
                return out;
            }
            lastExit = process.exitValue();
            lastOutput = output;
            deleteQuietly(out);
            if (!filter.isBlank()) {
                log.warn("音轨提取带滤镜失败（本机 ffmpeg 可能不支持 {}），退回无滤镜重试 - 输出: {}",
                        filter, output.length() > 500 ? output.substring(0, 500) : output.trim());
            }
        }

        log.warn("音轨提取失败 - 退出码: {}, 输出: {}", lastExit,
                lastOutput.length() > 800 ? lastOutput.substring(0, 800) : lastOutput.trim());
        throw new BusinessException("该视频没有可用的音轨，或格式无法解码，请确认视频能正常播放");
    }

    /**
     * 把 mp3 / aac 等压缩音频解码成 wav。
     *
     * <p>用 Java Sound 的 SPI（mp3 解码能力由 pom 里的 {@code mp3spi} 提供）；
     * 原始采样率原样保留 —— whisper.cpp 内部会自行重采样到 16kHz。</p>
     *
     * @return 解码后的 wav 临时文件；**解码失败返回 null**（调用方退回原文件，不让整轮识别中断）
     */
    private Path decodeToWav(Path source, Path workDir) {
        Path wav = null;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(source.toFile())) {
            wav = Files.createTempFile(workDir, "asr-", ".wav");
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
            log.info("音频已转 wav - 源格式: {}, 转换后大小: {} 字节",
                    in.getFormat(), Files.size(wav));
            return wav;
        } catch (Exception e) {
            log.warn("音频解码为 wav 失败（将退回原文件）- 文件: {}, 原因: {}",
                    source.getFileName(), e.getMessage());
            deleteQuietly(wav);
            return null;
        }
    }

    /** 前 16 字节的十六进制，用于日志里辨认真实容器格式 */
    private String headHex(byte[] data) {
        int n = Math.min(16, data.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }

    /**
     * 按文件头粗略嗅探真实格式。
     *
     * <p>必要性：小程序在**开发者工具**里录音，拿到的可能不是声明的 mp3，
     * 而是浏览器内核产出的 webm/ogg；真机上则通常是标准 mp3。
     * 有了这行日志，就能一眼看出该用哪种解码方式。</p>
     */
    private String sniffFormat(byte[] data) {
        if (data == null || data.length < 4) {
            return "空数据";
        }
        if (startsWith(data, "RIFF")) return "wav";
        if (startsWith(data, "OggS")) return "ogg/opus";
        if (startsWith(data, "ID3")) return "mp3(带ID3)";
        if (startsWith(data, "fLaC")) return "flac";
        if ((data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) return "webm/mkv";
        if (data.length > 11 && data[4] == 'f' && data[5] == 't'
                && data[6] == 'y' && data[7] == 'p') return "mp4/m4a";
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) return "mp3/aac(裸流)";
        return "未知";
    }

    private boolean startsWith(byte[] data, String magic) {
        if (data.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if ((data[i] & 0xFF) != (magic.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 真正执行一次识别，返回识别文本（可能为空串）。
     *
     * @param timeoutSeconds 识别超时。60 秒录音用 {@code app.voice.timeout-seconds}（180s）；
     *                       答辩视频整段转写要按视频时长放大（见 {@code app.video-analysis.budget-seconds}）
     * @param initialPrompt  术语气提示词；为空则用 {@code app.voice.prompt} 的默认值。
     *                       视频链路会传一份答辩术语表，专治「文字答辩→文字打变」这类同音字
     */
    private AsrOutcome runWhisper(Path audioFile, Path outputPrefix, long timeoutSeconds,
                                  String initialPrompt, String modelPathOverride)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(exePath);
        command.add("-m");
        command.add(UploadUtils.isBlank(modelPathOverride) ? modelPath : modelPathOverride);
        command.add("-f");
        command.add(audioFile.toAbsolutePath().toString());
        command.add("-l");
        command.add(appProperties.getVoice().getLanguage());
        command.add("-otxt");
        command.add("-of");
        command.add(outputPrefix.toAbsolutePath().toString());
        // 静默模式：不打印分段与进度，避免输出膨胀（也顺带避免管道缓冲区被写满）
        command.add("-np");

        boolean whisperCli = isWhisperCli();
        if (whisperCli) {
            // JSON：保留分段/时间戳/token 置信度，供质量闸门与后续证据展示使用。
            // 必须用 -ojf（full）：只加 -oj 时 transcription[].tokens 为空，拿不到置信度。
            // 旧版 main.exe 不支持该参数，故只对新引擎加。
            command.add("-ojf");
        }

        int threads = appProperties.getVoice().getThreads();
        if (threads > 0) {
            command.add("-t");
            command.add(String.valueOf(threads));
        }

        appendVadArgs(command, whisperCli);

        // 提示词（initial prompt）：引导简体输出 + 提供专业术语表。
        // 调用方给了专用提示词（视频术语表）就优先用它，否则退回全局 app.voice.prompt。
        String prompt = UploadUtils.isBlank(initialPrompt)
                ? appProperties.getVoice().getPrompt() : initialPrompt;
        if (!UploadUtils.isBlank(prompt)) {
            // 【2026-10-28】硬截断：超长 prompt 会让 whisper-cli 崩溃（见 MAX_PROMPT_CHARS 注释）
            if (prompt.length() > MAX_PROMPT_CHARS) {
                log.warn("ASR 提示词过长，已截断 - 原长度: {}, 上限: {}", prompt.length(), MAX_PROMPT_CHARS);
                prompt = prompt.substring(0, MAX_PROMPT_CHARS);
            }
            command.add("--prompt");
            command.add(prompt);
        }

        long start = System.currentTimeMillis();

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (output.length() < 4000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时读取中断，属预期
            }
        });
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new BusinessException("语音识别超时（超过 " + timeoutSeconds + " 秒），请缩短作答时长或稍后重试");
        }
        reader.join(1000);
        long cost = System.currentTimeMillis() - start;

        Path txtFile = Path.of(outputPrefix + ".txt");
        if (!Files.exists(txtFile)) {
            log.error("语音识别未产出结果 - 退出码: {}, 输出: {}", process.exitValue(), output);
            throw new BusinessException("语音识别失败，请重试或改用文字作答");
        }

        String rawText = Files.readString(txtFile, StandardCharsets.UTF_8).trim();
        // 统一转成简体：whisper 的中文输出简繁不稳定（同一段话时简时繁）；
        // 再按外置词表修正高置信度同音词（如「文字打变」→「文字答辩」）。
        String text = applyCorrections(toSimplified(rawText));
        AsrOutcome outcome = parseOutcome(text, Path.of(outputPrefix + ".json"));
        log.info("语音识别完成 - 退出码: {}, 耗时: {} ms, 文本长度: {}, 分段: {}, "
                        + "有效语音: {} ms, 平均置信度: {}",
                process.exitValue(), cost, text.length(), outcome.segmentCount(),
                outcome.speechMs(), String.format(Locale.ROOT, "%.3f", outcome.avgConfidence()));
        return outcome;
    }

    /** 只给支持 VAD 的新版 whisper-cli 追加 VAD 参数；模型缺失时静默跳过 */
    private void appendVadArgs(List<String> command, boolean whisperCli) {
        AppProperties.Voice voice = appProperties.getVoice();
        if (!whisperCli || !voice.isVadEnabled() || vadModelPath == null) {
            return;
        }
        command.add("--vad");
        command.add("--vad-model");
        command.add(vadModelPath);
        command.add("--vad-threshold");
        command.add(String.valueOf(voice.getVadThreshold()));
        command.add("--vad-min-speech-duration-ms");
        command.add(String.valueOf(voice.getVadMinSpeechMs()));
        command.add("--vad-min-silence-duration-ms");
        command.add(String.valueOf(voice.getVadMinSilenceMs()));
    }

    private boolean isWhisperCli() {
        return exePath != null && exePath.toLowerCase(Locale.ROOT).endsWith("whisper-cli.exe");
    }

    /**
     * 解析 whisper JSON：分段数、有效语音时长、平均 token 置信度、低置信分段数。
     *
     * <p>解析失败只降级为「没有质量统计」，绝不让转写本身失败。</p>
     */
    private AsrOutcome parseOutcome(String text, Path jsonFile) {
        if (!Files.exists(jsonFile)) {
            return AsrOutcome.unavailable(text);
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(jsonFile, StandardCharsets.UTF_8));
            JsonNode segments = root.path("transcription");
            if (!segments.isArray()) {
                return AsrOutcome.unavailable(text);
            }
            long speechMs = 0;
            double pSum = 0;
            long pCount = 0;
            int lowConfidenceSegments = 0;
            int segmentCount = 0;
            for (JsonNode segment : segments) {
                segmentCount++;
                long from = segment.path("offsets").path("from").asLong(0);
                long to = segment.path("offsets").path("to").asLong(0);
                // 语音时长按 token 的起止时间累加：VAD 分段的整体跨度会跨越静音，
                // 直接用 segment.to - segment.from 会把静音也算成"有效语音"（实测 120s/128s）。
                long segSpeechMs = 0;
                double segSum = 0;
                long segCount = 0;
                for (JsonNode token : segment.path("tokens")) {
                    String tokenText = token.path("text").asText("");
                    if (tokenText.isBlank() || tokenText.startsWith("[")) {
                        continue;
                    }
                    double p = token.path("p").asDouble(-1);
                    if (p < 0 || p > 1) {
                        continue;
                    }
                    long tokenFrom = token.path("offsets").path("from").asLong(0);
                    long tokenTo = token.path("offsets").path("to").asLong(0);
                    if (tokenTo > tokenFrom) {
                        segSpeechMs += tokenTo - tokenFrom;
                    }
                    segSum += p;
                    segCount++;
                }
                if (segSpeechMs > 0) {
                    speechMs += segSpeechMs;
                } else if (to > from) {
                    // 没有 token 时间戳时退回分段跨度（近似值）
                    speechMs += to - from;
                }
                if (segCount > 0) {
                    pSum += segSum;
                    pCount += segCount;
                    if (segSum / segCount < 0.6) {
                        lowConfidenceSegments++;
                    }
                }
            }
            double avgConfidence = pCount > 0 ? pSum / pCount : 0;
            return new AsrOutcome(text, true, speechMs, avgConfidence,
                    lowConfidenceSegments, segmentCount);
        } catch (Exception e) {
            log.warn("whisper JSON 解析失败（跳过质量统计）- 原因: {}", e.getMessage());
            return AsrOutcome.unavailable(text);
        }
    }

    /**
     * 一次识别的文本 + 质量指标。
     *
     * @param metricsAvailable false 表示 JSON 不可用（旧引擎/解析失败），调用方应跳过质量闸门
     */
    private record AsrOutcome(String text, boolean metricsAvailable, long speechMs,
                              double avgConfidence, int lowConfidenceSegments, int segmentCount) {
        static AsrOutcome unavailable(String text) {
            return new AsrOutcome(text, false, 0, 0, 0, 0);
        }
    }

    /**
     * 繁体转简体。
     *
     * <p>whisper 的中文输出简繁不稳定（同一段话有时简体、有时繁体），
     * 统一转成简体，学生看到的识别结果才一致。转换失败保留原文，不影响答辩。</p>
     */
    private String toSimplified(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return ZhConverterUtil.toSimple(text);
        } catch (Exception e) {
            log.warn("繁简转换失败（保留原文）- 原因: {}", e.getMessage());
            return text;
        }
    }

    /** 解析引擎路径：配置优先，其次按常见目录自动查找 */
    private String resolveExe() {
        String configured = appProperties.getVoice().getWhisperExe();
        if (!UploadUtils.isBlank(configured)) {
            if (Files.isRegularFile(Path.of(configured))) {
                return configured;
            }
            log.warn("application.yml 配置的 whisper 引擎路径不存在，改为自动查找 - {}", configured);
        }

        for (Path dir : searchDirs()) {
            for (String name : VoiceConstants.ENGINE_EXE_NAMES) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** 解析模型路径：配置优先，其次在引擎同目录下按优先级挑选 */
    private String resolveModel() {
        String configured = appProperties.getVoice().getWhisperModel();
        if (!UploadUtils.isBlank(configured)) {
            if (Files.isRegularFile(Path.of(configured))) {
                return configured;
            }
            log.warn("application.yml 配置的 whisper 模型路径不存在，改为自动查找 - {}", configured);
        }

        Path exeDir = Path.of(exePath).getParent();
        if (exeDir == null) {
            return null;
        }
        List<Path> dirs = List.of(exeDir.resolve("models"), exeDir);

        // 2026-10-20：配置的档位必须真正生效。
        // 旧实现只按固定优先级扫描，导致 installer 下载了 medium、运行时却仍选 small。
        String tier = appProperties.getVoice().getModel();
        if (!UploadUtils.isBlank(tier)) {
            String fileName = normalizeModelFileName(tier);
            for (Path dir : dirs) {
                Path candidate = dir.resolve(fileName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
            log.warn("配置的 whisper 模型档位 {} 未找到（{}），改为按优先级自动选择",
                    tier, fileName);
        }

        for (Path dir : dirs) {
            for (String name : VoiceConstants.MODEL_FILE_NAMES) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /**
     * 解析指定档位的模型路径（视频专用）。
     *
     * @param tier 空 = 使用默认模型；否则按 `ggml-<tier>.bin` 在引擎目录/models 查找
     * @return 找到的完整路径；未配置或找不到时退回 {@link #modelPath}
     */
    private String resolveModelForTier(String tier) {
        if (UploadUtils.isBlank(tier)) {
            return modelPath;
        }
        String fileName = normalizeModelFileName(tier);
        Path exeDir = Path.of(exePath).getParent();
        if (exeDir != null) {
            for (Path dir : List.of(exeDir.resolve("models"), exeDir)) {
                Path candidate = dir.resolve(fileName);
                if (Files.isRegularFile(candidate)) {
                    log.info("视频转写使用模型: {}（配置档位: {}）", candidate, tier);
                    return candidate.toString();
                }
            }
        }
        log.warn("视频专用 ASR 模型 {} 未找到（{}），退回默认模型 {}",
                tier, fileName, modelPath);
        return modelPath;
    }

    /** 把 `medium` / `ggml-medium` / `ggml-medium.bin` 统一成 `ggml-medium.bin` */
    private String normalizeModelFileName(String configured) {
        String name = configured.trim().toLowerCase(Locale.ROOT);
        if (!name.startsWith("ggml-")) {
            name = "ggml-" + name;
        }
        if (!name.endsWith(".bin")) {
            name = name + ".bin";
        }
        return name;
    }

    /**
     * 加载转写纠错词表：配置路径优先，其次 classpath:asr-corrections.txt。
     *
     * <p>格式 `错误=正确`，`#` 注释；加载失败只降级为不纠错，不影响识别。</p>
     */
    private Map<String, String> loadCorrections() {
        String configured = appProperties.getVoice().getCorrectionsFile();
        InputStream in = null;
        try {
            if (!UploadUtils.isBlank(configured)) {
                Path path = Path.of(configured);
                if (Files.isRegularFile(path)) {
                    in = Files.newInputStream(path);
                } else {
                    log.warn("配置的 ASR 纠错词表不存在，改用内置词表 - {}", configured);
                }
            }
            if (in == null) {
                in = getClass().getClassLoader().getResourceAsStream("asr-corrections.txt");
            }
            if (in == null) {
                return Map.of();
            }
            Map<String, String> rules = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int idx = line.indexOf('=');
                    if (idx <= 0 || idx >= line.length() - 1) {
                        continue;
                    }
                    String from = line.substring(0, idx).trim();
                    String to = line.substring(idx + 1).trim();
                    if (!from.isEmpty() && !to.isEmpty() && !from.equals(to)) {
                        rules.put(from, to);
                    }
                }
            }
            if (rules.isEmpty()) {
                return Map.of();
            }
            log.info("ASR 转写纠错词表已加载 - {} 条（来源: {}）", rules.size(),
                    UploadUtils.isBlank(configured) ? "classpath:asr-corrections.txt" : configured);
            return Map.copyOf(rules);
        } catch (Exception e) {
            log.warn("ASR 纠错词表加载失败（不纠错）- 原因: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 应用纠错词表；只在确有替换时打一行长度日志（不打印转写内容） */
    private String applyCorrections(String text) {
        if (text == null || correctionRules.isEmpty()) {
            return text;
        }
        String corrected = AsrTextCorrector.correct(text, correctionRules);
        if (!corrected.equals(text)) {
            log.info("ASR 纠错词表生效 - 原长度: {}, 纠错后长度: {}",
                    text.length(), corrected.length());
        }
        return corrected;
    }

    /** 解析 VAD 模型路径：配置优先 → 引擎同目录/models → 常见 whisper 目录/models */
    private String resolveVadModel() {
        String configured = appProperties.getVoice().getVadModel();
        if (!UploadUtils.isBlank(configured)) {
            if (Files.isRegularFile(Path.of(configured))) {
                return configured;
            }
            log.warn("application.yml 配置的 VAD 模型路径不存在，改为自动查找 - {}", configured);
        }
        for (Path base : searchDirs()) {
            for (Path dir : List.of(base.resolve("models"), base)) {
                for (String name : VoiceConstants.VAD_MODEL_FILE_NAMES) {
                    Path candidate = dir.resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                }
            }
        }
        return null;
    }

    /** 解析 ffmpeg 路径：配置优先 → PATH → 引擎同目录 / 项目 whisper 目录 */
    private String resolveFfmpeg() {
        String configured = appProperties.getVoice().getFfmpegExe();
        if (!UploadUtils.isBlank(configured) && isRegularFile(configured)) {
            return configured;
        }

        String fromPath = findInPath(VoiceConstants.FFMPEG_EXE_NAME);
        if (fromPath != null) {
            return fromPath;
        }

        List<Path> dirs = new ArrayList<>();
        if (exePath != null) {
            Path exeDir = Path.of(exePath).getParent();
            if (exeDir != null) {
                dirs.add(exeDir);
            }
        }
        addDirSafe(dirs, Path.of(fileStorageService.rootDir(), "whisper"));
        addDirSafe(dirs, Path.of(System.getProperty("user.dir", "."), "whisper"));
        for (Path dir : dirs) {
            Path candidate = dir.resolve(VoiceConstants.FFMPEG_EXE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** 在系统 PATH 中查找可执行文件 */
    private String findInPath(String fileName) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.trim(), fileName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (Exception ignored) {
                // 非法 PATH 项直接跳过
            }
        }
        return null;
    }

    private boolean isRegularFile(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 用 ffmpeg 把任意容器格式转成 16kHz 单声道 wav（识别引擎最稳的输入）。
     *
     * <p><b>这是必须的一步</b>：微信开发者工具（模拟器）录出的是 <b>webm/opus</b> ——
     * 浏览器内核产物，会忽略小程序里指定的格式；Java 自带解码器与 whisper 都不认 webm，
     * 只有 ffmpeg 能处理。真机的 mp3 / aac 也统一交给它，比 Java 解码更稳。</p>
     *
     * @return 转码后的 wav 临时文件；ffmpeg 不存在或转码失败返回 null（调用方退回 Java 解码）
     */
    private Path convertWithFfmpeg(Path source, Path workDir) {
        if (ffmpegPath == null) {
            return null;
        }
        Path out = null;
        try {
            out = Files.createTempFile(workDir, "asr-", ".wav");
            ProcessBuilder builder = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", source.toAbsolutePath().toString(),
                    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                    "-loglevel", "error",
                    out.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();

            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 已加 -loglevel error，输出极少，进程结束后再读不会阻塞
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("转码超时（" + FFMPEG_TIMEOUT_SECONDS + " 秒）");
            }
            if (process.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                throw new IOException("退出码 " + process.exitValue() + "：" + output.trim());
            }
            log.info("ffmpeg 转码完成 - {} → 16kHz wav（{} 字节）", source.getFileName(), Files.size(out));
            return out;
        } catch (Exception e) {
            log.warn("ffmpeg 转码失败（将退回 Java 解码）- 原因: {}", e.getMessage());
            deleteQuietly(out);
            return null;
        }
    }

    /** 引擎查找目录：存储根目录、项目目录、常见磁盘目录、用户主目录 */
    private List<Path> searchDirs() {
        List<Path> dirs = new ArrayList<>();
        addDirSafe(dirs, Path.of(fileStorageService.rootDir(), "whisper"));
        addDirSafe(dirs, Path.of(System.getProperty("user.dir", "."), "whisper"));
        for (String drive : List.of("F", "D", "E", "C")) {
            addDirSafe(dirs, Path.of(drive + ":\\whisper"));
        }
        addDirSafe(dirs, Path.of("D:\\tools\\whisper"));
        addDirSafe(dirs, Path.of(System.getProperty("user.home", "."), "whisper"));
        return dirs;
    }

    private void addDirSafe(List<Path> dirs, Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        } catch (Exception e) {
            // 路径非法（如盘符不存在）直接跳过
        }
    }

    /** 临时文件目录（识别用的音频与中间结果都放这里，用完即删） */
    private Path tempDir() {
        try {
            return Files.createDirectories(
                    Path.of(System.getProperty("java.io.tmpdir"), "ai-helper-asr"));
        } catch (IOException e) {
            throw new BusinessException("无法创建临时目录：" + e.getMessage());
        }
    }

    /** 扩展名白名单化：只允许字母数字，避免任何路径拼接风险 */
    private String normalizeExt(String format) {
        if (UploadUtils.isBlank(format)) {
            return "mp3";
        }
        String ext = format.trim().toLowerCase(Locale.ROOT).replace(".", "");
        return ext.matches("[a-z0-9]{1,5}") ? ext : "mp3";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件删除失败（忽略）: {}", path);
        }
    }
}
