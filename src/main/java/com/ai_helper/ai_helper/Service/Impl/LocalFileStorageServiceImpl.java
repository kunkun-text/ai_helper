package com.ai_helper.ai_helper.Service.Impl;

import cn.hutool.core.util.IdUtil;
import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 本地磁盘文件存储实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final AppProperties appProperties;

    /** 存储根目录（已规范化为绝对路径） */
    private Path root;

    @PostConstruct
    public void init() {
        this.root = resolveRoot();
        log.info("本地文件存储已就绪 - 根目录: {}, URL 前缀: {}",
                root, appProperties.getStorage().getUrlPrefix());
    }

    /**
     * 决定存储根目录（三级降级，保证换电脑拷贝项目后仍能正常运行）。
     *
     * <ol>
     *   <li>优先用 {@code app.storage.root} 显式配置的目录；</li>
     *   <li>若该目录不可用（最典型：把项目拷到另一台电脑，而 yml 里还写着原机器的 F 盘），
     *       则按 {@code app.storage.preferred-drives} 的顺序挑第一个存在的磁盘，
     *       在其下创建 {@code app.storage.folder-name} 文件夹；</li>
     *   <li>仍失败则退到用户主目录，至少保证应用能启动。</li>
     * </ol>
     */
    private Path resolveRoot() {
        AppProperties.Storage storage = appProperties.getStorage();
        String folderName = UploadUtils.isBlank(storage.getFolderName())
                ? "ai wordplace"
                : storage.getFolderName().trim();

        // 1) 显式配置
        String configured = storage.getRoot();
        Path prepared = prepare(configured);
        if (prepared != null) {
            log.info("使用配置指定的存储目录：{}", prepared);
            return prepared;
        }
        if (!UploadUtils.isBlank(configured)) {
            log.warn("配置的存储目录不可用（这台电脑可能没有该磁盘），改为自动选择 - 配置值: {}", configured);
        }

        // 2) 按优先磁盘顺序自动创建
        if (storage.getPreferredDrives() != null) {
            for (String drive : storage.getPreferredDrives()) {
                if (UploadUtils.isBlank(drive)) {
                    continue;
                }
                String letter = drive.trim().replace(":", "").replace("\\", "").replace("/", "");
                if (letter.isEmpty()) {
                    continue;
                }
                Path candidate = prepare(letter + ":/" + folderName);
                if (candidate != null) {
                    log.info("已在 {} 盘自动创建存储目录：{}", letter, candidate);
                    return candidate;
                }
            }
        }

        // 3) 兜底：用户主目录
        Path fallback = prepare(Paths.get(System.getProperty("user.home"), folderName).toString());
        if (fallback != null) {
            log.warn("未找到可用磁盘，已退到用户主目录：{}", fallback);
            return fallback;
        }
        throw new IllegalStateException("无法确定可用的文件存储目录，请显式配置 app.storage.root");
    }

    /**
     * 尝试把给定目录准备好（创建 + 可写校验）。
     *
     * @return 规范化后的绝对路径；不可用时返回 null，由调用方继续降级
     */
    private Path prepare(String dir) {
        if (UploadUtils.isBlank(dir)) {
            return null;
        }
        String value = dir.trim();
        try {
            Path path = Paths.get(value);
            // 盘符不存在时直接判失败，避免在 Windows 上卡到超时才报错
            Path rootPath = path.getRoot();
            if (rootPath != null && !Files.exists(rootPath)) {
                return null;
            }
            Path abs = path.toAbsolutePath().normalize();
            Files.createDirectories(abs);
            if (!Files.isWritable(abs)) {
                return null;
            }
            return abs;
        } catch (Exception e) {
            log.debug("存储目录不可用：{}（{}）", value, e.getMessage());
            return null;
        }
    }

    @Override
    public String rootDir() {
        return root.toString();
    }

    @Override
    public String rootUri() {
        return root.toUri().toString();
    }

    @Override
    public String save(String subDir, String fileName, InputStream in) throws IOException {
        String relativePath = subDir + "/" + fileName;
        Path target = resolveSafe(relativePath);
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("文件已保存 - 路径: {}, 大小: {} 字节", target, Files.size(target));
        return relativePath;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        Path target = resolveSafe(relativePath);
        if (!Files.exists(target)) {
            throw new IOException("文件不存在：" + relativePath);
        }
        return Files.newInputStream(target);
    }

    @Override
    public boolean delete(String relativePath) {
        String relative = toRelativePath(relativePath);
        if (relative == null) {
            log.warn("跳过删除：不是本存储管理的路径 - {}", relativePath);
            return false;
        }
        // 历史数据里可能存的是完整 URL，先剥掉 URL 前缀再删
        Path target = resolveSafe(relative);
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("文件已删除 - {}", target);
            }
            return true;
        } catch (IOException e) {
            log.error("文件删除失败 - {}", target, e);
            return false;
        }
    }

    @Override
    public boolean exists(String relativePath) {
        String relative = toRelativePath(relativePath);
        return relative != null && Files.exists(resolveSafe(relative));
    }

    @Override
    public long size(String relativePath) {
        String relative = toRelativePath(relativePath);
        if (relative == null) {
            return -1;
        }
        Path target = resolveSafe(relative);
        try {
            return Files.exists(target) ? Files.size(target) : -1;
        } catch (IOException e) {
            log.error("读取文件大小失败 - {}", target, e);
            return -1;
        }
    }

    @Override
    public String toPublicUrl(String relativePath) {
        String relative = toRelativePath(relativePath);
        if (relative == null) {
            return "";
        }
        String prefix = appProperties.getStorage().getUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        // 去掉 prefix 结尾多余的 '/'
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + relative;
    }

    @Override
    public String toRelativePath(String urlOrStoredValue) {
        if (urlOrStoredValue == null) {
            return null;
        }
        String value = urlOrStoredValue.trim();
        if (value.isEmpty()) {
            return null;
        }

        String prefix = appProperties.getStorage().getUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }

        // 已经是本存储的对外 URL：剥掉前缀
        if (value.startsWith(prefix + "/")) {
            return value.substring(prefix.length() + 1);
        }

        // 其它绝对地址（历史阿里云 OSS 等）：无法反解
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) {
            return null;
        }

        // 还剩一种情况：库里存的是「相对路径」，直接可用
        return value.startsWith("/") ? value.substring(1) : value;
    }

    @Override
    public String createWithSize(String subDir, String fileName, long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("文件大小不能为负数：" + size);
        }
        String relativePath = subDir + "/" + fileName;
        Path target = resolveSafe(relativePath);
        Files.createDirectories(target.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(size);
        }
        log.info("已预分配文件 - 路径: {}, 大小: {} 字节", target, size);
        return relativePath;
    }

    @Override
    public void writeAt(String relativePath, long offset, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("分片内容为空");
        }
        if (offset < 0) {
            throw new IOException("写入偏移量非法：" + offset);
        }
        Path target = resolveSafe(relativePath);
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long position = offset;
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }

    @Override
    public String generateFileName(String originalFileName) {
        String suffix = "";
        if (originalFileName != null) {
            int dot = originalFileName.lastIndexOf('.');
            // 注意：dot 必须大于 0，否则 ".gitignore" 这类会被误当成扩展名，或 dot=-1 时 substring 越界
            if (dot > 0 && dot < originalFileName.length() - 1) {
                suffix = "." + originalFileName.substring(dot + 1).toLowerCase();
            }
        }
        return IdUtil.simpleUUID() + suffix;
    }

    /**
     * 把相对路径解析为根目录下的绝对路径，并拦截目录穿越。
     */
    private Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件路径：" + relativePath);
        }
        return target;
    }
}
