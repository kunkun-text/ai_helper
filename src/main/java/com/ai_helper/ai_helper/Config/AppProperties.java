package com.ai_helper.ai_helper.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 应用自定义配置：文件存储、上传规则、鉴权参数
 *
 * <p>对应 application.yml 中的 app.* 配置段。</p>
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    /** 文件存储配置 */
    private Storage storage = new Storage();

    /** 上传规则配置 */
    private Upload upload = new Upload();

    /** 鉴权配置 */
    private Auth auth = new Auth();

    @Data
    public static class Storage {
        /**
         * 存储根目录（绝对路径），如 `F:/ai wordplace`。
         *
         * <p>留空则按 {@link #preferredDrives} 自动选择磁盘并在其下创建 {@link #folderName}。
         * 即使显式配置了，一旦该路径不可用（典型场景：把项目拷到另一台没有 F 盘的电脑），
         * 也会自动降级为自动选择，避免"换台电脑就用不了"。</p>
         */
        private String root = "";

        /** 自动选择存储磁盘时，需要创建的文件夹名 */
        private String folderName = "ai wordplace";

        /** 自动选择存储磁盘的优先顺序（Windows 盘符，不带冒号） */
        private List<String> preferredDrives = new ArrayList<>(Arrays.asList("F", "D", "E", "C"));

        /** 对外访问 URL 前缀，由 WebConfig 映射到存储根目录 */
        private String urlPrefix = "/files";
    }

    @Data
    public static class Upload {
        /** 视频上传规则 */
        private FileRule video = FileRule.videoDefaults();

        /** 答辩报告上传规则 */
        private FileRule report = FileRule.reportDefaults();
    }

    @Data
    public static class FileRule {
        /** 单文件大小上限（字节） */
        private long maxSize = 50L * 1024 * 1024;

        /** 允许的扩展名（小写、不含点） */
        private List<String> allowedExts = new ArrayList<>();

        /** 分片大小（字节），仅分片上传使用 */
        private long chunkSize = 5L * 1024 * 1024;

        /**
         * 视频的默认规则。
         *
         * <p>为什么要在代码里给默认值：`application.yml` 被 `.gitignore` 排除，
         * 换一台机器 clone 下来后 `app.upload.*` 是空的，白名单为空会导致
         * 所有上传都被拒（提示"服务端未配置可上传的文件格式"）。
         * 有默认值后 yml 变成「可选覆盖」，不至于换机器就用不了。</p>
         */
        static FileRule videoDefaults() {
            FileRule rule = new FileRule();
            rule.setMaxSize(500L * 1024 * 1024);
            rule.setChunkSize(5L * 1024 * 1024);
            rule.setAllowedExts(new ArrayList<>(Arrays.asList(
                    "mp4", "mov", "avi", "mkv", "flv", "wmv", "m4v", "3gp")));
            return rule;
        }

        /** 答辩报告的默认规则（常见文档格式 + 50MB） */
        static FileRule reportDefaults() {
            FileRule rule = new FileRule();
            rule.setMaxSize(50L * 1024 * 1024);
            rule.setAllowedExts(new ArrayList<>(Arrays.asList(
                    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")));
            return rule;
        }
    }

    @Data
    public static class Auth {
        /** 登录 token 有效期（分钟）。太短会导致长答辩/大文件上传中途失效 */
        private long tokenTtlMinutes = 120;

        /** 无需登录即可访问的路径（Ant 风格），由 WebConfig 读取 */
        private List<String> excludePaths = new ArrayList<>();
    }
}
