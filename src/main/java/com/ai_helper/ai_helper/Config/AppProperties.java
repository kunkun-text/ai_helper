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

    /** 答辩续答配置 */
    private Defense defense = new Defense();

    /** 语音答辩配置（TTS 播报 + ASR 语音识别） */
    private Voice voice = new Voice();

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

        // 【E4 · 2026-10-05】原 excludePaths 字段注释称"由 WebConfig 读取"，实际 WebConfig 从未读取
        // （受保护路径是 WebConfig.PROTECTED_PATHS 常量），为避免配置项骗人，直接删除该字段。
    }

    @Data
    public static class Defense {
        /**
         * 续答时间窗口（分钟）。
         *
         * <p>只有「最后一次作答发生在该窗口内」的未完成场次才允许续答。
         * 否则历史遗留的 pending 脏记录（例如已答满 10 轮却没写回收尾状态的记录）
         * 会被当成"要接着答的场次"，学生一进答辩页就直接跳到追问/收尾阶段。</p>
         */
        private long resumeWindowMinutes = 30;
    }

    /**
     * 语音答辩配置。
     *
     * <p>语音识别（学生说话 → 文字）由后端调用 <b>whisper.cpp</b> 完成：
     * 小程序原生录音（不需要任何插件）→ 上传音频 → 后端跑 whisper 得到文字 → 回填给学生。
     * 引擎与模型都是本机文件、离线运行，不依赖小程序主体资质、也不联网。</p>
     */
    @Data
    public static class Voice {
        /**
         * whisper.cpp 可执行文件路径（whisper-cli.exe 或旧版的 main.exe）。
         *
         * <p>留空时按常见目录自动查找（见 {@code WhisperAsrServiceImpl}），
         * 换机器只需改这一处配置，或把引擎放到默认目录。</p>
         */
        private String whisperExe = "";

        /** 模型文件（ggml-*.bin）路径。留空时在引擎同目录的 models/ 下自动挑选 */
        private String whisperModel = "";

        /**
         * ffmpeg 可执行文件路径（可选，但强烈建议装上）。
         *
         * <p>作用：把小程序录出的各种容器格式（**webm / mp3 / aac**）统一转成 16kHz wav。
         * 为什么必须：**微信开发者工具（模拟器）录出的是 webm/opus**（浏览器内核产物，
         * 会忽略小程序指定的格式），Java 自带解码器与 whisper 都不认 webm，只有 ffmpeg 能处理；
         * 真机的 mp3/aac 同样可以交给它，比 Java 解码更稳。</p>
         *
         * <p>留空时自动查找：引擎同目录、whisper 目录、PATH。</p>
         */
        private String ffmpegExe = "";

        /** 识别语言（zh = 中文） */
        private String language = "zh";

        /**
         * 识别提示词（whisper 的 initial prompt）。
         *
         * <p>双重作用：① 引导模型输出**简体中文**（whisper 默认可能输出繁体，
         * 同一段话时简时繁）；② 提供**专业术语表** —— 实测加提示词后
         * 「Watelotlib」能纠正成「Matplotlib」、「Pandas」规范成「pandas」。</p>
         *
         * <p>注意：whisper 的 prompt 约 224 token 上限，别写太长。</p>
         */
        private String prompt = "以下是《大数据技术与原理》课程答辩中，学生回答考官提问的语音转写，"
                + "口语化表达，请用规范的简体中文输出。常见术语："
                + "Hadoop HDFS MapReduce YARN Hive HBase ZooKeeper Kafka Spark Flume Sqoop "
                + "NameNode DataNode ResourceManager NodeManager RDD Shuffle ETL "
                + "数据仓库 数据清洗 数据挖掘 数据可视化 时间序列 趋势分析 月均值 年均值 "
                + "AQI PM2.5 PM10 超标 优良率 数据倾斜 副本 分片 容错 高可用 集群 主从架构 "
                + "批处理 流处理 离线计算 实时计算 pandas numpy matplotlib CSV SQL MySQL Redis";

        /** 单次识别超时（秒）：超时即放弃本次识别，前端提示"没听清，重录" */
        private long timeoutSeconds = 180;

        /** 单次录音时长上限（秒），仅提示前端，超过会自动停止录音 */
        private int maxRecordSeconds = 60;
    }
}
