package com.ai_helper.ai_helper.util;

/**
 * 上传相关的通用工具方法（视频分片上传与报告直传共用，避免各写一套）。
 */
public final class UploadUtils {

    private UploadUtils() {
    }

    /**
     * 取小写扩展名（不含点）。无扩展名或形如 ".gitignore" 时返回空串，不会越界。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * 把字节数转成给用户看的可读大小，用于错误提示。
     */
    public static String readableSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
