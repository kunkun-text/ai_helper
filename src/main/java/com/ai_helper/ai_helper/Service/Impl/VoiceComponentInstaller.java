package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.constant.VoiceConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 语音组件「自动下载器」——让新机器 clone 下来即可开箱使用语音答辩。
 *
 * <p><b>背景</b>：语音识别声依赖三样本机文件，总量约 550MB：
 * ① whisper.cpp 引擎（whisper-cli.exe + 一堆 DLL，约 15MB）；
 * ② ggml 模型（ggml-small.bin，约 487MB）；
 * ③ ffmpeg（约 40MB，开发者工具录的 webm 必须靠它转码）。
 * 这些体积远超 Git 仓库单文件上限（GitHub 100MB），不可能随代码提交，
 * 所以采用「后端启动时自动补齐」——与 {@code ollama pull} 的体验一致。</p>
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>幂等：已存在的组件不重复下载（引擎 / 模型 / ffmpeg 分别判断）；</li>
 *   <li>不阻塞启动：由 {@code WhisperAsrServiceImpl} 在应用就绪后开后台线程调用；</li>
 *   <li>进度可查：{@link #percent()} / {@link #stage()} 暴露给前端，避免学生以为"坏了"；</li>
 *   <li>多镜像：模型走 hf-mirror 优先（国内可达），ffmpeg / 引擎走 GitHub 与常见源；</li>
 *   <li>失败不致命：引擎或模型缺失只是语音不可用，应用照常启动，日志给出手动安装指引。</li>
 * </ul>
 *
 * <p><b>安全</b>：下载地址全部是代码内固定常量（不接受任何用户输入），解压时校验条目路径
 * 不得逃逸目标目录（防 Zip Slip）。</p>
 */
@Slf4j
@Component
public class VoiceComponentInstaller {

    /** 每下载多少字节打一条进度日志（避免刷屏） */
    private static final long LOG_STEP_BYTES = 20L * 1024 * 1024;

    /** 引擎压缩包固定兜底地址（GitHub API 查不到时使用；v1.9.2 确认带预编译包） */
    private static final String ENGINE_ZIP_FALLBACK =
            "https://github.com/ggerganov/whisper.cpp/releases/download/v1.9.2/whisper-blas-bin-x64.zip";

    /** 引擎压缩包候选名（优先 BLAS 版：CPU 上识别更快；其次纯 CPU 版） */
    private static final List<String> ENGINE_ZIP_NAMES =
            List.of("whisper-blas-bin-x64.zip", "whisper-bin-x64.zip");

    /** 模型镜像（hf-mirror 国内可达，huggingface 官方兜底） */
    private static final List<String> MODEL_MIRRORS = List.of(
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/");

    /** ffmpeg 压缩包（gyan.dev 官方 win64 构建，BtbN 兜底） */
    private static final List<String> FFMPEG_ZIP_URLS = List.of(
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip");

    @Resource
    private AppProperties appProperties;

    /** 同一时刻只允许一个安装任务 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 当前阶段描述（供前端展示） */
    private volatile String stage = "";

    /** 整体进度 0-100（单调不减） */
    private volatile int percent = 0;

    /** 最近一次失败原因（空串 = 无失败） */
    private volatile String lastError = "";

    /** 本次安装的目标目录 */
    private volatile String targetDir = "";

    public boolean isRunning() {
        return running.get();
    }

    public int percent() {
        return percent;
    }

    public String stage() {
        return stage;
    }

    public String lastError() {
        return lastError;
    }

    public String targetDir() {
        return targetDir;
    }

    /**
     * 幂等安装：只补齐缺失的组件（引擎 / 模型 / ffmpeg）。
     *
     * <p>同步执行、可能耗时数分钟（取决于网速），调用方必须放在后台线程。</p>
     *
     * @return true 表示执行后**引擎与模型**均已就绪（ffmpeg 缺失不致命，单独记日志）
     */
    public boolean install() {
        if (!running.compareAndSet(false, true)) {
            log.info("语音组件安装已在进行中，忽略本次重复触发");
            return false;
        }
        try {
            Path dir = resolveInstallDir();
            targetDir = dir.toString();
            Files.createDirectories(dir);
            log.info("语音组件自动安装开始 - 目标目录: {}, 模型档位: {}", dir, modelName());

            // ---------- 1/3 引擎 ----------
            if (hasEngine(dir)) {
                log.info("[1/3] 语音识别引擎已存在，跳过下载 - {}", dir);
            } else {
                stage = "下载语音识别引擎";
                downloadEngine(dir);
            }
            updatePercent(30);

            // ---------- 2/3 模型 ----------
            Path modelDir = dir.resolve("models");
            Files.createDirectories(modelDir);
            if (hasModel(modelDir)) {
                log.info("[2/3] 语音识别模型已存在，跳过下载 - {}", modelDir);
            } else {
                stage = "下载语音识别模型 " + modelName() + "（体积较大，请耐心等待）";
                downloadModel(modelDir);
            }
            updatePercent(88);

            // ---------- 3/3 ffmpeg（尽力而为） ----------
            if (hasFfmpeg(dir)) {
                log.info("[3/3] ffmpeg 已存在，跳过安装 - {}", dir);
            } else {
                stage = "准备音频转码组件 ffmpeg";
                installFfmpeg(dir);
            }
            updatePercent(100);

            boolean ok = hasEngine(dir) && hasModel(modelDir);
            if (ok) {
                lastError = "";
                stage = "完成";
                log.info("语音组件自动安装结束 - 引擎与模型均已就绪（目录: {}，ffmpeg: {}）",
                        dir, hasFfmpeg(dir) ? "已就绪" : "缺失");
            } else {
                stage = "未完成";
                log.warn("语音组件自动安装结束，但引擎或模型仍缺失（目录: {}）", dir);
            }
            return ok;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stage = "失败";
            log.error("语音组件自动安装失败：{}。可手动运行 scripts/install-whisper.ps1 安装", lastError, e);
            return false;
        } finally {
            running.set(false);
        }
    }

    // ============================================================================================
    // 引擎
    // ============================================================================================

    /** 下载 whisper.cpp 引擎并解压到目标目录 */
    private void downloadEngine(Path dir) throws IOException {
        String url = resolveEngineZipUrl();
        log.info("下载语音识别引擎：{}", url);
        Path zip = downloadToTemp(url, "whisper-engine.zip", "引擎", 0, 28);
        Path extractDir = Files.createTempDirectory("voice-engine-");
        try {
            unzip(zip, extractDir);
            Path exe = findFile(extractDir, VoiceConstants.ENGINE_EXE_NAMES);
            if (exe == null) {
                throw new IOException("下载的压缩包中未找到 whisper 可执行文件");
            }
            // 引擎与其依赖的 DLL 在同一目录，整体拷贝过去
            copyDirectory(exe.getParent(), dir);
            log.info("引擎安装完成：{}", dir.resolve(exe.getFileName()));
        } finally {
            deleteRecursively(zip);
            deleteRecursively(extractDir);
        }
    }

    /**
     * 解析引擎压缩包地址：优先用 GitHub API 找最近的带预编译包的 release，
     * 失败则退回固定版本（直接写死，保证离线可用性）。
     */
    private String resolveEngineZipUrl() {
        try {
            String json = httpGetString(
                    "https://api.github.com/repos/ggerganov/whisper.cpp/releases?per_page=30");
            JsonNode releases = new ObjectMapper().readTree(json);
            for (JsonNode release : releases) {
                for (String wanted : ENGINE_ZIP_NAMES) {
                    for (JsonNode asset : release.path("assets")) {
                        if (wanted.equals(asset.path("name").asText())) {
                            String url = asset.path("browser_download_url").asText();
                            log.info("已定位引擎包：{}（release {}）", wanted, release.path("tag_name").asText());
                            return url;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询 whisper.cpp release 失败（改用固定版本兜底）：{}", e.getMessage());
        }
        return ENGINE_ZIP_FALLBACK;
    }

    // ============================================================================================
    // 模型
    // ============================================================================================

    /** 下载 ggml 模型（多镜像依次重试） */
    private void downloadModel(Path modelDir) throws IOException {
        String fileName = modelName();
        Path dest = modelDir.resolve(fileName);
        Path tmp = null;
        IOException last = null;
        for (String mirror : MODEL_MIRRORS) {
            String url = mirror + fileName;
            try {
                log.info("下载语音识别模型：{}", url);
                tmp = downloadToTemp(url, fileName, "模型", 30, 58);
                break;
            } catch (IOException e) {
                last = e;
                log.warn("模型镜像下载失败（{}）：{}", mirror, e.getMessage());
            }
        }
        if (tmp == null) {
            throw last == null ? new IOException("模型下载失败") : last;
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        log.info("模型安装完成：{}（{}）", dest, humanSize(Files.size(dest)));
    }

    // ============================================================================================
    // ffmpeg
    // ============================================================================================

    /**
     * 安装 ffmpeg：优先复用本机已有的（很多软件自带），找不到再下载。
     *
     * <p>失败不抛异常 —— ffmpeg 缺失只影响「开发者工具录的 webm」，
     * 真机的 mp3 仍走 Java 解码，不该因此让整个自动安装失败。</p>
     */
    private void installFfmpeg(Path dir) {
        Path dest = dir.resolve(VoiceConstants.FFMPEG_EXE_NAME);

        Path local = findLocalFfmpeg();
        if (local != null) {
            try {
                Files.copy(local, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("复用本机已有 ffmpeg：{} → {}", local, dest);
                return;
            } catch (IOException e) {
                log.warn("复制本机 ffmpeg 失败（改为下载）：{}", e.getMessage());
            }
        }

        for (String url : FFMPEG_ZIP_URLS) {
            Path zip = null;
            Path extractDir = null;
            try {
                log.info("下载 ffmpeg：{}", url);
                zip = downloadToTemp(url, "ffmpeg.zip", "ffmpeg", 88, 12);
                extractDir = Files.createTempDirectory("voice-ffmpeg-");
                unzip(zip, extractDir);
                Path exe = findFile(extractDir, List.of(VoiceConstants.FFMPEG_EXE_NAME));
                if (exe == null) {
                    throw new IOException("压缩包中未找到 ffmpeg.exe");
                }
                Files.copy(exe, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("ffmpeg 安装完成：{}", dest);
                return;
            } catch (IOException e) {
                log.warn("ffmpeg 下载安装失败（{}）：{}", url, e.getMessage());
            } finally {
                deleteRecursively(zip);
                deleteRecursively(extractDir);
            }
        }
        log.warn("ffmpeg 未能安装：真机 mp3 作答仍可用（走 Java 解码），"
                + "但开发者工具录制的 webm 无法识别。可手动把 ffmpeg.exe 放到：{}", dir);
    }

    /** 在本机寻找现成的 ffmpeg.exe（PATH → LOCALAPPDATA / APPDATA 下三层目录） */
    private Path findLocalFfmpeg() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(dir.trim(), VoiceConstants.FFMPEG_EXE_NAME);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                    // 非法 PATH 项直接跳过
                }
            }
        }
        for (String envKey : new String[]{"LOCALAPPDATA", "APPDATA"}) {
            String root = System.getenv(envKey);
            if (root == null || root.isBlank()) {
                continue;
            }
            try (Stream<Path> stream = Files.find(Path.of(root), 4, (p, attr) ->
                    attr.isRegularFile()
                            && VoiceConstants.FFMPEG_EXE_NAME.equalsIgnoreCase(p.getFileName().toString()))) {
                Path hit = stream.findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            } catch (Exception ignored) {
                // 目录不可读等情况跳过
            }
        }
        return null;
    }

    // ============================================================================================
    // 下载 / 解压 / 目录工具
    // ============================================================================================

    /**
     * 下载到临时文件（流式写盘，绝不把大文件读进内存）。
     *
     * @param label 日志标签（引擎 / 模型 / ffmpeg）
     * @param base  进度基线（0-100）
     * @param span  本次下载占用的进度区间
     */
    private Path downloadToTemp(String url, String fileName, String label, int base, int span) throws IOException {
        Path tmp = Files.createTempFile("voice-dl-", "-" + fileName);
        HttpClient client = buildClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ai-helper-voice-bootstrap")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[1 << 16];
                long read = 0;
                long lastLog = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    read += n;
                    if (total > 0) {
                        updatePercent(base + (int) (span * read / total));
                    }
                    if (read - lastLog >= LOG_STEP_BYTES) {
                        lastLog = read;
                        log.info("{} 下载中 ... {} / {}", label, humanSize(read),
                                total > 0 ? humanSize(total) : "大小未知");
                    }
                }
            }
            return tmp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteRecursively(tmp);
            throw new IOException("下载被中断", e);
        } catch (IOException e) {
            deleteRecursively(tmp);
            throw e;
        } catch (RuntimeException e) {
            deleteRecursively(tmp);
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 构造 HTTP 客户端：跟随重定向（hf-mirror 会 302 到 CDN），可选用代理 */
    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL);

        String proxy = appProperties.getVoice().getProxy();
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy.trim());
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
            } catch (Exception e) {
                log.warn("代理配置无效（将直连）：{} - {}", proxy, e.getMessage());
            }
        }
        return builder.build();
    }

    /** 读取一个小文本响应（仅用于 GitHub API） */
    private String httpGetString(String url) throws IOException, InterruptedException {
        HttpClient client = buildClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ai-helper-voice-bootstrap")
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** 解压 zip（含 Zip Slip 防护：条目路径不得逃逸目标目录） */
    private void unzip(Path zip, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    throw new IOException("压缩包包含非法路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** 在目录下递归查找指定文件名的文件（限深 6 层） */
    private Path findFile(Path root, List<String> names) throws IOException {
        try (Stream<Path> stream = Files.find(root, 6, (p, attr) -> {
            if (!attr.isRegularFile()) {
                return false;
            }
            String fileName = p.getFileName().toString();
            return names.stream().anyMatch(n -> n.equalsIgnoreCase(fileName));
        })) {
            return stream.findFirst().orElse(null);
        }
    }

    /** 递归拷贝目录内容 */
    private void copyDirectory(Path source, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path target = dest.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除（失败忽略） */
    private void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 目录不存在等情况忽略
        }
    }

    // ============================================================================================
    // 判定 / 命名 / 进度
    // ============================================================================================

    /** 目标安装目录：配置优先 → 存储盘下的 whisper → 用户主目录下的 whisper */
    private Path resolveInstallDir() {
        String configured = appProperties.getVoice().getInstallDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        for (String drive : appProperties.getStorage().getPreferredDrives()) {
            Path root = Path.of(drive + ":\\");
            if (Files.exists(root)) {
                return root.resolve("whisper");
            }
        }
        return Path.of(System.getProperty("user.home", "."), "whisper");
    }

    /** 模型文件名：把配置档位（tiny/base/small/medium 或完整文件名）归一成 ggml-xxx.bin */
    private String modelName() {
        String configured = appProperties.getVoice().getModel();
        if (configured == null || configured.isBlank()) {
            return "ggml-small.bin";
        }
        String name = configured.trim().toLowerCase(Locale.ROOT);
        if (!name.startsWith("ggml-")) {
            name = "ggml-" + name;
        }
        if (!name.endsWith(".bin")) {
            name = name + ".bin";
        }
        return name;
    }

    private boolean hasEngine(Path dir) {
        return VoiceConstants.ENGINE_EXE_NAMES.stream()
                .anyMatch(n -> Files.isRegularFile(dir.resolve(n)));
    }

    private boolean hasModel(Path modelDir) {
        return VoiceConstants.MODEL_FILE_NAMES.stream()
                .anyMatch(n -> Files.isRegularFile(modelDir.resolve(n)));
    }

    private boolean hasFfmpeg(Path dir) {
        return Files.isRegularFile(dir.resolve(VoiceConstants.FFMPEG_EXE_NAME));
    }

    /** 进度只增不减，避免多阶段回退造成前端显示跳动 */
    private void updatePercent(int value) {
        int clamped = Math.max(0, Math.min(100, value));
        if (clamped > percent) {
            percent = clamped;
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 0) {
            return "大小未知";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
