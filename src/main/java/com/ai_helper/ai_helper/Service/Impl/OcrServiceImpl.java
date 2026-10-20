package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.OcrService;
import com.ai_helper.ai_helper.pojo.vo.ComponentStatusVo;
import com.ai_helper.ai_helper.util.DocImageOcr;
import com.ai_helper.ai_helper.util.WindowsOcr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OCR 组件（tesseract）的查找、自动安装与调用。
 *
 * <p><b>为什么选 tesseract</b>：项目全程 {@code mvn -o} 离线编译，本地仓库没有任何 OCR 依赖；
 * 引入 Java OCR 库会直接打断构建。tesseract 是外部可执行程序，与 whisper.cpp / ffmpeg
 * 完全同款——不改 pom、不影响离线编译。</p>
 *
 * <p><b>安装方式</b>：Windows 版只有安装器（Inno Setup），用
 * {@code /VERYSILENT /DIR="..."} 静默装到指定目录，再补下中文语言包
 * {@code tessdata_fast}（每份约数 MB）。全部动作在后台线程完成，不阻塞启动。</p>
 *
 * <p><b>失败口径</b>：安装失败只记日志并让 {@link #available()} 保持 false，
 * 评析照常进行（图片文字缺席，成绩单会注明）。绝不因为 OCR 让评析任务失败。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrServiceImpl implements OcrService {

    /** Windows 安装器固定版本（Inno Setup，支持静默安装到指定目录） */
    private static final String INSTALLER_URL =
            "https://digi.bib.uni-mannheim.de/tesseract/tesseract-ocr-w64-setup-5.3.3.20231005.exe";

    /** 语言包来源：tessdata_fast（体积小、速度快，适合 8G 低功耗机） */
    private static final String TESSDATA_MIRROR =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/";

    /** 中文与英文必须都有：报告里常混排英文技术名词 */
    private static final List<String> REQUIRED_LANGUAGES = List.of("chi_sim", "eng");

    private static final int DOWNLOAD_TIMEOUT_SECONDS = 300;
    private static final int INSTALL_TIMEOUT_SECONDS = 300;

    private final AppProperties appProperties;

    private final AtomicBoolean installing = new AtomicBoolean(false);

    /** Windows 自带 OCR（懒加载；null = 非 Windows 或脚本释放失败） */
    private volatile WindowsOcr windowsOcr;

    /** 引擎选择结果（null = 尚未探测；安装完成后会被重置） */
    private volatile Engine engine;

    /** 最近一次失败原因（空串 = 无失败） */
    private volatile String lastError = "";

    /** 当前安装阶段与进度（供状态接口展示） */
    private volatile String stage = "";

    private volatile int percent = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        installIfMissingAsync();
    }

    @Override
    public boolean available() {
        return resolveEngine() != Engine.NONE;
    }

    @Override
    public String unavailableReason() {
        if (available()) {
            return "";
        }
        if (installing.get()) {
            return "图片识别组件正在后台准备，稍后重试即可";
        }
        if (!lastError.isBlank()) {
            return "图片识别组件不可用：" + lastError;
        }
        if (windows() == null) {
            return "未检测到 Windows 自带 OCR，tesseract 也未安装（图片内容不参与评分）";
        }
        return "Windows 自带 OCR 缺少中文语言包，tesseract 也未就绪（可在系统设置中安装中文语言包）";
    }

    @Override
    public String recognize(byte[] raw, String extension) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        Engine current = resolveEngine();
        if (current == Engine.WINDOWS) {
            WindowsOcr ocr = windows();
            return ocr == null ? null : ocr.recognize(raw, extension);
        }
        if (current == Engine.TESSERACT) {
            String exe = executable();
            if (exe == null) {
                return null;
            }
            int timeout = appProperties.getReportDoc().getOcrTimeoutSeconds();
            return new DocImageOcr(exe, timeout).recognize(raw);
        }
        return null;
    }

    @Override
    public void installIfMissingAsync() {
        AppProperties.ReportDoc cfg = appProperties.getReportDoc();
        if (!cfg.isEnabled() || !cfg.isOcrEnabled()) {
            return;
        }
        Engine current = resolveEngine();
        if (current != Engine.NONE) {
            log.info("图片识别引擎已就绪：{}（无需下载）", current);
            return;
        }
        // Windows 自带 OCR 可用时不再下载任何东西：现网 tesseract 官方源与 GitHub raw 均不可达
        WindowsOcr win = windows();
        if (win != null && win.available()) {
            engine = Engine.WINDOWS;
            log.info("图片识别引擎已就绪：WINDOWS（系统自带 OCR，无需下载）");
            return;
        }
        if (!installing.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(this::install, "ocr-component-installer");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================================================================
    // 引擎选择
    // ==================================================================

    private enum Engine {
        /** 不可用 */
        NONE,
        /** Windows 自带 OCR（零下载，优先） */
        WINDOWS,
        /** 外部 tesseract（可选兜底） */
        TESSERACT
    }

    /**
     * 选择引擎：配置 {@code ocr-engine} 优先，其次 Windows 自带 OCR，最后 tesseract。
     * 结果缓存（探测要起一次 PowerShell，约 0.5~1 秒）；安装完成后会重置重算。
     */
    private Engine resolveEngine() {
        Engine cached = engine;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (engine != null) {
                return engine;
            }
            AppProperties.ReportDoc cfg = appProperties.getReportDoc();
            String configured = cfg.getOcrEngine() == null ? "auto" : cfg.getOcrEngine().trim();
            boolean forceTesseract = "tesseract".equalsIgnoreCase(configured);
            boolean forceWindows = "windows".equalsIgnoreCase(configured);
            boolean windowsReady = !forceTesseract && windowsReady();

            if (forceWindows || (configured.isEmpty() || "auto".equalsIgnoreCase(configured))) {
                if (windowsReady) {
                    engine = Engine.WINDOWS;
                    return engine;
                }
            } else if (windowsReady) {
                engine = Engine.WINDOWS;
                return engine;
            }
            engine = tesseractReady() ? Engine.TESSERACT : Engine.NONE;
            return engine;
        }
    }

    private boolean windowsReady() {
        WindowsOcr win = windows();
        return win != null && win.available();
    }

    private WindowsOcr windows() {
        WindowsOcr local = windowsOcr;
        if (local == null) {
            synchronized (this) {
                if (windowsOcr == null) {
                    windowsOcr = WindowsOcr.create();
                }
                local = windowsOcr;
            }
        }
        return local;
    }

    /** tesseract 引擎与中英语言包是否齐全 */
    private boolean tesseractReady() {
        return executable() != null && REQUIRED_LANGUAGES.stream()
                .allMatch(lang -> Files.isRegularFile(tessdataDir().resolve(lang + ".traineddata")));
    }

    // ==================================================================
    // 安装
    // ==================================================================

    private void install() {
        try {
            Path dir = installDir();
            Files.createDirectories(dir);
            stage = "准备安装";
            percent = 5;
            log.info("图片识别组件自动安装开始 - 目标目录: {}", dir);

            if (executable() == null) {
                stage = "下载 tesseract 安装包";
                percent = 15;
                Path installer = downloadInstaller();
                percent = 60;
                stage = "安装 tesseract 引擎";
                runSilentInstall(installer, dir);
                percent = 80;
                Files.deleteIfExists(installer);
            } else {
                log.info("图片识别引擎已存在，跳过安装 - {}", executable());
                percent = 80;
            }

            Path tessdata = tessdataDir();
            Files.createDirectories(tessdata);
            stage = "下载语言包";
            percent = 85;
            for (String language : REQUIRED_LANGUAGES) {
                Path target = tessdata.resolve(language + ".traineddata");
                if (Files.isRegularFile(target) && Files.size(target) > 1024) {
                    log.info("语言包已存在，跳过下载 - {}", target);
                    continue;
                }
                download(URI.create(TESSDATA_MIRROR + language + ".traineddata"), target, language + " 语言包");
            }

            if (available()) {
                lastError = "";
                stage = "完成";
                percent = 100;
                log.info("图片识别组件已就绪 - 引擎: {}, 语言包目录: {}", executable(), tessdata);
            } else {
                lastError = "安装后仍未检测到引擎或语言包";
                stage = "未完成";
                log.warn("图片识别组件安装结束但未就绪 - 目录: {}", dir);
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stage = "失败";
            log.warn("图片识别组件自动安装失败（图片内容将不参与评分）：{}", lastError, e);
        } finally {
            // 重新探测：可能从 NONE 变为 TESSERACT
            engine = null;
            installing.set(false);
        }
    }

    @Override
    public ComponentStatusVo status() {
        Engine current = resolveEngine();
        boolean installed = current != Engine.NONE;
        String location;
        String message;
        if (current == Engine.WINDOWS) {
            location = "Windows 自带 OCR（系统组件，零下载）";
            message = "已安装：使用系统自带 OCR，支持中文";
        } else if (current == Engine.TESSERACT) {
            location = executable() == null ? installDir().toString() : executable();
            message = "已安装：外部 tesseract 引擎";
        } else if (installing.get()) {
            location = installDir().toString();
            message = "未安装，后台安装中";
        } else {
            location = installDir().toString();
            message = unavailableReason();
        }
        return new ComponentStatusVo("ocr", "图片识别（OCR）", installed,
                installing.get(), installed ? 100 : percent, stage, location, message);
    }

    /** 静默安装 tesseract 到指定目录（Inno Setup 参数） */
    private void runSilentInstall(Path installer, Path dir) throws Exception {
        List<String> command = List.of(
                installer.toAbsolutePath().toString(),
                "/VERYSILENT",
                "/SUPPRESSMSGBOXES",
                "/NORESTART",
                "/DIR=\"" + dir.toAbsolutePath() + "\"");
        log.info("开始静默安装图片识别引擎 - 目标: {}", dir);
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("安装超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("安装器退出码 " + process.exitValue());
        }
        if (executable() == null) {
            throw new IllegalStateException("安装完成但未找到 tesseract.exe");
        }
    }

    private Path downloadInstaller() throws Exception {
        Path target = Files.createTempFile("tesseract-setup-", ".exe");
        download(URI.create(INSTALLER_URL), target, "tesseract 安装包");
        return target;
    }

    private void download(URI uri, Path target, String label) throws Exception {
        log.info("下载{}：{}", label, uri);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .GET()
                .build();
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException(label + " 下载失败，HTTP " + response.statusCode());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("{} 下载完成：{}（{} 字节）", label, target, Files.size(target));
    }

    // ==================================================================
    // 路径解析（与语音组件同款：配置优先 → 第一个存在的盘 → 用户目录）
    // ==================================================================

    private Path installDir() {
        String configured = appProperties.getReportDoc().getOcrInstallDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        for (String drive : appProperties.getStorage().getPreferredDrives()) {
            Path root = Path.of(drive + ":\\");
            if (Files.exists(root)) {
                return root.resolve("ocr");
            }
        }
        return Path.of(System.getProperty("user.home", "."), "ocr");
    }

    /** 引擎路径：兼容直接解压与安装器两种目录结构 */
    private String executable() {
        Path dir = installDir();
        Path direct = dir.resolve("tesseract.exe");
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().toString();
        }
        Path nested = dir.resolve("Tesseract-OCR").resolve("tesseract.exe");
        if (Files.isRegularFile(nested)) {
            return nested.toAbsolutePath().toString();
        }
        return null;
    }

    /**
     * 语言包目录：tesseract 默认在**可执行文件同级**的 {@code tessdata/} 查找语言包，
     * 所以装好后必须按 exe 的真实位置推算，不能按配置目录猜（静默安装可能多套一层
     * {@code Tesseract-OCR/}）。
     */
    private Path tessdataDir() {
        String exe = executable();
        if (exe != null) {
            return Path.of(exe).getParent().resolve("tessdata");
        }
        Path dir = installDir();
        Path nested = dir.resolve("Tesseract-OCR");
        return Files.isDirectory(nested) ? nested.resolve("tessdata") : dir.resolve("tessdata");
    }
}
