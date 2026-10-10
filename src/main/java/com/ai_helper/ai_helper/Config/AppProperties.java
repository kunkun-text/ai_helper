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

    /** 评分判分阈值配置（chatController 判分复核链路使用） */
    private Scoring scoring = new Scoring();

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

        /**
         * 缺失组件时是否**启动后自动下载**（引擎约 15MB + 模型约 487MB + ffmpeg 约 40MB）。
         *
         * <p>默认开启，目的是让新机器 clone 下来「开 Redis + 开 Ollama + 启动后端」即可用语音，
         * 无需手动跑安装脚本（引擎/模型体积远超 Git 仓库单文件上限，无法随代码提交）。
         * 首次启动会在后台下载并打进度日志，不阻塞应用启动。</p>
         */
        private boolean autoInstall = true;

        /**
         * 语音组件安装目录。
         *
         * <p>留空时自动选择：优先本机存在的存储盘（如 `F:\whisper`），与
         * {@code WhisperAsrServiceImpl} 的自动查找目录保持一致。</p>
         */
        private String installDir = "";

        /**
         * whisper 模型档位：`tiny` / `base` / `small` / `medium`（也可直接写 `ggml-xxx.bin`）。
         *
         * <p>默认 small：中文答辩场景准确率与速度最均衡，但模型约 487MB；
         * 8G 低功耗机若嫌首次下载慢/识别慢，可改 base（约 142MB，快一倍）。</p>
         */
        private String model = "small";

        /** 下载代理（可选，如 `http://127.0.0.1:7890`）；留空 = 直连 */
        private String proxy = "";
    }

    /**
     * 评分判分阈值。
     *
     * <p>【2026-10-10 · 阈值外置】原为 chatController 内的硬编码常量，改为可配置，
     * 不同学科/课题表现不一时可不重新编译微调。全部是经验值，代码默认值与外置前的
     * 常量值完全一致；application.yml 不配置也能按默认值运行。</p>
     */
    @Data
    public static class Scoring {

        /**
         * N45 一致性保护阈值：模型自评总分 ≥ 该值却标 [错误] 时，标记与评分打架，
         * 不再无条件信标记砍半，改交一次复核仲裁（复核判[错误]→半分 / 判[切题]→维持原分）。
         *
         * <p>触发案例：defenseId=297 第 3 轮，AQI 正确版答案模型自给 36 分却标 [错误]，被砍成 18。
         * 调低则保护面变窄（30~31 分的误杀案例会被漏掉）；调高则"明显错误拿高分"更难被纠正。</p>
         */
        private double wrongAnswerHalfScoreMaxTotal = 32.0;

        /**
         * 答非所问复核触发门槛（N51）：回答与当前题的概念覆盖率低于该值 → 疑似答非所问，
         * 追加 OFF_TARGET 复核。经验值，实测后可微调。
         */
        private double offTargetCoverageThreshold = 0.25;

        /**
         * 跨轮重复作答判定阈值（N47）：归一化后与本场此前任一轮答案的二元组 Dice ≥ 该值
         * 即判「复读旧答案」，走固定零分流程、不调评分模型。
         *
         * <p>实测 297 场第 5 轮把第 2 轮约 500 字答案原样重发（仅个别数字变动）照拿 34 分。
         * 阈值取 0.8（与防作弊 Dice 同款经验值）：正常作答即使引用自己此前的表述，
         * 主体内容不同，Dice 不会到 0.8。</p>
         */
        private double crossRoundRepeatDice = 0.8;

        /**
         * 跑题复核救回的最低相关性门槛（N53）：回答与当前题的二元组覆盖率低于该值时，
         * 视为"明显跑题的长回答"，不进入复核救回、维持 0 分。
         *
         * <p>实测 300 场第 6/8 轮：通篇"今天天气不错…做菜"类的跑题长回答满足「0 分 + ≥20字」
         * 复核条件，被复核误救成 28/14 分。切题回答总会复述题目关键词（覆盖率明显高于该值）。</p>
         */
        private double rescueMinCoverage = 0.05;

        /**
         * 点评"接地"最低命中数（N46/N49）：点评里必须出现至少该数量的「本轮答案」二元组，
         * 否则判幻觉/模板话点评，强制重写。
         *
         * <p>实测模板话/幻觉点评与本轮答案的二元组交集为 0~1，真实点评引用 1~2 个具体词
         * 即有 3+ 命中，故默认 3。</p>
         */
        private int groundedMinHits = 3;

        /** 追问主题级去重：两道题共享的有效关键词达到该数，视为同主题（N48）。 */
        private int followUpTopicOverlapMin = 2;

        /** 追问主题级去重：关键词 Jaccard 相似度达到该值，视为同主题（N48）。 */
        private double followUpTopicJaccard = 0.34;

        /**
         * 【路线① · 2026-10-10】是否启用「要点逐项核验」三档校正。
         *
         * <p>背景：3B 做"整体档位判断"会退化（实测恒返回 D、把全对判 D），但做"逐项命中判定"稳定得多
         * （实验：全对→全命中，错误/无关→全未命中）。开启后，预设题轮（有标准答案）会：① 首次生成该题
         * 3~5 条要点清单并缓存；② 每轮逐条判 命中/未命中/矛盾；③ 命中率低于阈值 → 判"错误"档压缩总分。
         * 仅预设题触发，追问轮不受影响。</p>
         *
         * <p>成本：清单生成每题一次；核验每轮一次，增量约 2~5 秒（单轮 ≤20 秒约束内）。关闭即回旧行为。</p>
         */
        private boolean checklistEnabled = true;

        /**
         * 【路线①】命中率低于该值 → 判"错误"档。
         * 【2026-10-10 实测标定 · defenseId=334】全对 3/4=0.75、半对半错 2/3=0.667、夹杂错误 2/3=0.667、全错 0/3=0，
         * 故取 0.70 卡在 0.667 与 0.75 之间（3B 命中率只有 0/0.25/0.33/0.5/0.67/0.75/1 几个离散档，分界偏窄）。
         */
        private double checklistErrorHitRateMax = 0.70;

        /** 【路线①】判"错误"档时的最终总分上限（五档表大错×0.5 → 7.5~15，取 15）。 */
        private double checklistErrorFinalCap = 15.0;
    }
}
