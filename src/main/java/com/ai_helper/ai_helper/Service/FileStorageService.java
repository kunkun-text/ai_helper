package com.ai_helper.ai_helper.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储抽象层。
 *
 * <p>刻意屏蔽具体存储介质（当前为本地磁盘），使上层业务不感知存储实现，
 * 便于后续扩展对象存储等其它介质而不改动业务代码。</p>
 *
 * <p>约定：对外一律使用「相对存储路径」（如 {@code videos/xxx.mp4}），
 * 数据库也只存相对路径，避免服务器 IP/域名变化导致历史数据全部失效。</p>
 */
public interface FileStorageService {

    /** 存储根目录的绝对路径（仅供日志与排查使用） */
    String rootDir();

    /**
     * 存储根目录的 {@code file:} URI，供 WebConfig 映射为静态资源。
     *
     * <p>由实现返回、而不是让调用方拿配置自己拼：存储根目录可能是运行时自动选定的
     * （见 {@code AppProperties.Storage#preferredDrives}），照配置拼会拼出错误路径。</p>
     */
    String rootUri();

    /**
     * 保存文件（同名覆盖）。
     *
     * @param subDir   子目录，如 videos / reports（不允许出现 .. 等穿越字符）
     * @param fileName 文件名（不允许出现路径分隔符）
     * @param in       输入流，由调用方负责关闭
     * @return 相对存储路径，如 {@code videos/xxx.mp4}
     */
    String save(String subDir, String fileName, InputStream in) throws IOException;

    /**
     * 打开文件输入流，文件不存在时抛 IOException。
     */
    InputStream open(String relativePath) throws IOException;

    /**
     * 删除文件。文件本就不存在时同样返回 true（幂等，避免调用方重复处理）。
     */
    boolean delete(String relativePath);

    /** 文件是否存在 */
    boolean exists(String relativePath);

    /** 文件大小（字节），不存在时返回 -1 */
    long size(String relativePath);

    /** 相对路径 → 对外可访问 URL，如 {@code /files/videos/xxx.mp4} */
    String toPublicUrl(String relativePath);

    /**
     * 对外 URL 或库中存量值 → 相对存储路径。
     *
     * <p>用于兼容历史数据：早期的值可能是阿里云 OSS 的绝对地址（http/https 开头），
     * 这类非本存储管理的地址无法反解，返回 null，调用方应跳过文件删除等操作。</p>
     */
    String toRelativePath(String urlOrStoredValue);

    /** 生成一个不会冲突的文件名（保留原扩展名） */
    String generateFileName(String originalFileName);

    /**
     * 预分配一个指定大小的文件（分片上传用）。
     *
     * <p>本地存储实现为真正的空间预分配；若后续扩展为对象存储等介质，
     * 该操作可退化为空实现——那类存储由自身的分片机制管理空间。</p>
     *
     * @return 相对存储路径
     */
    String createWithSize(String subDir, String fileName, long size) throws IOException;

    /**
     * 在文件的指定偏移处写入一段数据（分片上传用），与 {@link #createWithSize} 配套。
     *
     * <p>非本地存储介质可实现为空操作。</p>
     */
    void writeAt(String relativePath, long offset, byte[] data) throws IOException;
}
