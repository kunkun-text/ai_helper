package com.ai_helper.ai_helper.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows 自带 OCR（{@code Windows.Media.Ocr}）的桥接（2026-10-20）。
 *
 * <p><b>为什么需要它</b>：tesseract 的官方下载源与 GitHub raw 在现网被拦截（实测 403 / 连接超时），
 * 而 Windows 10/11 自带 OCR 引擎，中文系统默认带 {@code zh-Hans-CN} 语言包——
 * <b>零下载、零依赖</b>，比再装一套 tesseract 更稳。</p>
 *
 * <p>实现方式：Java 把图片写成临时文件 → 调 {@code powershell.exe} 执行随包分发的
 * {@code scripts/windows-ocr.ps1}（WinRT 异步 API 需要 PowerShell 做桥）→
 * 脚本把识别结果以 UTF-8 写文件 → Java 读回。用文件而不是 stdout，
 * 是为了绕开控制台代码页（{@code chcp 936}）把中文变成乱码。</p>
 *
 * <p>失败一律返回 null，不抛异常：图片是评分增强项。</p>
 */
public final class WindowsOcr {

    private static final String SCRIPT_RESOURCE = "/scripts/windows-ocr.ps1";

    private static final int TIMEOUT_SECONDS = 30;

    private static final int MAX_TEXT_CHARS = 1500;

    private final Path workDir;

    private final Path script;

    private volatile Boolean supported;

    private WindowsOcr(Path workDir, Path script) {
        this.workDir = workDir;
        this.script = script;
    }

    /** 当前环境是否可能使用（非 Windows 直接 false） */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("windows");
    }

    /**
     * 创建实例；脚本从 classpath 释放到临时目录。
     *
     * @return 无法准备脚本时返回 null
     */
    public static WindowsOcr create() {
        if (!isWindows()) {
            return null;
        }
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir", "."), "ai-helper-ocr");
            Files.createDirectories(dir);
            Path script = dir.resolve("windows-ocr.ps1");
            try (InputStream in = WindowsOcr.class.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (in == null) {
                    return null;
                }
                Path temp = dir.resolve("windows-ocr.ps1.tmp");
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, script, StandardCopyOption.REPLACE_EXISTING);
            }
            return new WindowsOcr(dir, script);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 是否支持中文识别（{@code zh-Hans-CN} 等）。结果缓存，只探测一次。
     */
    public boolean available() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (supported != null) {
                return supported;
            }
            supported = probe();
            return supported;
        }
    }

    private boolean probe() {
        Path out = null;
        try {
            out = Files.createTempFile(workDir, "probe-", ".txt");
            Files.deleteIfExists(out);
            int code = run(List.of("-Probe", "-Out", out.toAbsolutePath().toString()));
            if (code != 0 || !Files.isRegularFile(out)) {
                return false;
            }
            String content = read(out);
            for (String line : content.split("\\R")) {
                if (line.trim().toLowerCase(Locale.ROOT).startsWith("zh")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            deleteQuietly(out);
        }
    }

    /**
     * 识别一张图片里的文字。
     *
     * @param raw       原始图片字节（png/jpg/gif/bmp/tiff 等 BitmapDecoder 支持的格式）
     * @param extension 扩展名（不含点），为空按 png 处理
     * @return 识别文本；不可用或无文字时返回 null
     */
    public String recognize(byte[] raw, String extension) {
        if (raw == null || raw.length == 0 || !available()) {
            return null;
        }
        Path image = null;
        Path out = null;
        try {
            String ext = extension == null || extension.isBlank() ? "png" : extension;
            image = Files.createTempFile(workDir, "src-", "." + ext);
            Files.write(image, raw);
            out = Files.createTempFile(workDir, "out-", ".txt");
            Files.deleteIfExists(out);

            int code = run(List.of("-Path", image.toAbsolutePath().toString(),
                    "-Out", out.toAbsolutePath().toString()));
            if (code != 0 || !Files.isRegularFile(out)) {
                return null;
            }
            String text = read(out);
            if (text.isBlank()) {
                return "";
            }
            return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        } catch (Exception e) {
            return null;
        } finally {
            deleteQuietly(image);
            deleteQuietly(out);
        }
    }

    private int run(List<String> scriptArgs) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(powershell());
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toAbsolutePath().toString());
        command.addAll(scriptArgs);

        // 合并 stderr：结果从文件读，合并可避免脚本报错时 stderr 缓冲写满导致进程阻塞
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path candidate = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return "powershell.exe";
    }

    private String read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").trim();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响结果
        }
    }
}
