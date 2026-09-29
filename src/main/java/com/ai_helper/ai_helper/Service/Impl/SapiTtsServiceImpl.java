package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.TtsService;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 <b>Windows SAPI（System.Speech）</b> 的离线语音合成。
 *
 * <p><b>为什么选它</b>：项目是纯 Java + Maven 的 Windows 部署，机器上没有 Python、
 * 也不希望为 TTS 额外装服务。Windows 自带的 SAPI 无需任何依赖、完全离线，
 * 中文系统上默认带中文语音（Microsoft Huihui 等），符合"8G 低功耗 + 不增加常驻进程"的部署红线。</p>
 *
 * <p><b>安全要点（务必保持）</b>：待合成文本**绝不拼进命令行** ——
 * 文本先写入临时文件，PowerShell 脚本通过 {@code -File} 执行、以**参数**方式接收文件路径。
 * 这样即使文本里含引号、分号、反引号也无法形成命令注入。
 * 同理，脚本内容本身为纯 ASCII，避免 PowerShell 5.1 读取含中文 .ps1 时的乱码坑。</p>
 *
 * <p><b>性能</b>：一次合成 = 启动 powershell（约 0.5~1 秒）+ SAPI 朗读。
 * 结果按文本内容哈希缓存到 {@code tts/} 目录，**同一道题只会真正合成一次**。</p>
 */
@Slf4j
@Service
public class SapiTtsServiceImpl implements TtsService {

    /** 合成文本上限（字符）：题目/总结都很短，超长直接截断，避免被滥用拖慢机器 */
    private static final int MAX_TEXT_LENGTH = 300;

    /** 单次合成最长等待时间（秒）：超时即放弃本次播报，不让前端干等 */
    private static final long TIMEOUT_SECONDS = 30L;

    /** 音频在存储根目录下的子目录 */
    private static final String SUB_DIR = "tts";

    /** PowerShell 脚本（纯 ASCII，含中文的文本一律走外部文件传入） */
    private static final String PS_SCRIPT = String.join("\n",
            "param([string]$TextFile, [string]$OutFile)",
            "Add-Type -AssemblyName System.Speech",
            "$text = Get-Content -Raw -Encoding UTF8 -Path $TextFile",
            "if ([string]::IsNullOrWhiteSpace($text)) { exit 2 }",
            "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer",
            "try {",
            "  $voice = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1",
            "  if ($voice) { $synth.SelectVoice($voice.VoiceInfo.Name) }",
            "  $synth.Rate = 0",
            "  $synth.SetOutputToWaveFile($OutFile)",
            "  $synth.Speak($text)",
            "} finally {",
            "  $synth.Dispose()",
            "}",
            "exit 0");

    /** 按文件名加锁：不同文本可并行合成，同一文本只合成一次（避免并发写坏同一个文件） */
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    @Resource
    private FileStorageService fileStorageService;

    @Override
    public String synthesizeToUrl(String text) {
        if (UploadUtils.isBlank(text)) {
            return "";
        }
        String content = text.trim();
        if (content.length() > MAX_TEXT_LENGTH) {
            content = content.substring(0, MAX_TEXT_LENGTH);
        }

        String fileName = md5(content) + ".wav";
        String relativePath = SUB_DIR + "/" + fileName;

        // 命中缓存：直接给出可播放地址（绝大多数轮次走到这里）
        if (fileStorageService.exists(relativePath)) {
            return fileStorageService.toPublicUrl(relativePath);
        }

        Object lock = fileLocks.computeIfAbsent(fileName, k -> new Object());
        try {
            synchronized (lock) {
                if (fileStorageService.exists(relativePath)) {
                    return fileStorageService.toPublicUrl(relativePath);
                }
                Path wav = synthesize(content);
                try (InputStream in = Files.newInputStream(wav)) {
                    fileStorageService.save(SUB_DIR, fileName, in);
                } finally {
                    deleteQuietly(wav);
                }
                log.info("语音合成完成 - 文本长度: {}, 存储路径: {}", content.length(), relativePath);
                return fileStorageService.toPublicUrl(relativePath);
            }
        } catch (Exception e) {
            // 播报失败不是致命错误：屏幕上有完整文字，前端静默跳过即可
            log.error("语音合成失败（本次跳过播报） - 文本: {}", content, e);
            return "";
        } finally {
            fileLocks.remove(fileName);
        }
    }

    /** 真正执行一次合成，返回临时 wav 文件（调用方负责删除） */
    private Path synthesize(String text) throws IOException, InterruptedException {
        Path workDir = Files.createDirectories(
                Path.of(System.getProperty("java.io.tmpdir"), "ai-helper-tts"));
        Path script = ensureScript(workDir);
        Path textFile = Files.createTempFile(workDir, "tts-text-", ".txt");
        Path outFile = workDir.resolve("tts-out-" + System.nanoTime() + ".wav");

        Files.writeString(textFile, text, StandardCharsets.UTF_8);
        deleteQuietly(outFile);

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toAbsolutePath().toString(),
                    textFile.toAbsolutePath().toString(),
                    outFile.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            process = builder.start();

            // 必须异步读输出：若输出填满管道缓冲区，进程会卡住导致 waitFor 一直等到超时
            StringBuilder output = new StringBuilder();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader reader1 = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader1.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时读取中断，属预期
                }
            });
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("语音合成超时（" + TIMEOUT_SECONDS + " 秒）");
            }
            reader.join(1000);

            if (process.exitValue() != 0) {
                throw new IOException("语音合成失败，退出码 " + process.exitValue()
                        + "，输出：" + output);
            }
            if (!Files.exists(outFile) || Files.size(outFile) == 0) {
                throw new IOException("语音合成未生成音频文件");
            }
            return outFile;
        } finally {
            deleteQuietly(textFile);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 首次使用时把脚本落到临时目录（纯 ASCII 写入，规避 PowerShell 读取中文脚本的编码坑） */
    private Path ensureScript(Path workDir) throws IOException {
        Path script = workDir.resolve("tts.ps1");
        if (Files.exists(script) && Files.size(script) > 0) {
            return script;
        }
        Files.write(script, PS_SCRIPT.getBytes(StandardCharsets.US_ASCII));
        return script;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件删除失败（忽略）: {}", path);
        }
    }

    /** 文本内容哈希：同一段文字复用同一个音频文件（天然缓存，且文件名不暴露文本内容） */
    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 一定存在，这里只为兜底不抛异常
            return Integer.toHexString(text.hashCode());
        }
    }
}
