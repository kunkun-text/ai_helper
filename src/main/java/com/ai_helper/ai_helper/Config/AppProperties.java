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

    /** 上传报告文档的 AI 评析配置（2026-10-16 · 报告五维评分第一期） */
    private ReportDoc reportDoc = new ReportDoc();

    /**
     * 材料方向判定：预设题覆盖（2026-10-27）。
     *
     * <p>与具体课题无关——判据是"本课题的预设题在材料里有没有被阐释"，
     * 所以 A/B/C 任何课题都走同一套；报告、视频转写、未来任何学生提交的材料都能复用。</p>
     */
    private MaterialCoverage materialCoverage = new MaterialCoverage();

    /** 答辩视频的 AI 评分配置（2026-10-20 · VID 系列：听声识别 → 五维成绩单） */
    private VideoAnalysis videoAnalysis = new VideoAnalysis();

    /** 云端 AI 评分配置（2026-10-20：GLM / DeepSeek 文本评分，本地 3B 兜底） */
    private Ai ai = new Ai();

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

        /**
         * 答辩报告的默认规则（50MB，**只收 .docx**，2026-10-20 收口）。
         *
         * <p><b>为什么只剩一种</b>：<br>
         * ① PDF / doc / xls / ppt 在零依赖前提下抽不出文字（PDFBox、POI scratchpad 都会
         * 破坏 {@code mvn -o} 离线编译）；<br>
         * ② 「下载报告模板」下发的只能是 docx，模板类型必须等于可分析类型，
         * 否则学生填完还得转换，口径自相矛盾；<br>
         * ③ docx 信息密度最高（段落顺序完整），pptx 一页一句、xlsx 表格会塌成文本流。<br>
         * 留多种格式只会让学生传完才发现拿不到分，故统一为 docx 并给可操作提示。</p>
         *
         * <p><b>白名单只影响上传</b>：历史上已上传的 pptx/xlsx/txt 报告仍可评析，
         * {@code DocTextExtractor} 的解析能力保留，不随白名单删除。</p>
         */
        static FileRule reportDefaults() {
            FileRule rule = new FileRule();
            rule.setMaxSize(50L * 1024 * 1024);
            rule.setAllowedExts(new ArrayList<>(Arrays.asList("docx")));
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

        /** ASR 并发排队等待上限（秒）：whisper 同时只跑 1 个任务，等不到就提示稍后重试 */
        private long asrQueueWaitSeconds = 3;

        /** 组件自动下载的连接超时（秒） */
        private long downloadConnectTimeoutSeconds = 30;

        /** 组件自动下载单次请求读取超时（秒） */
        private long downloadTimeoutSeconds = 40;

        /**
         * 【N78 · 2026-10-15】是否把最近一次录音的原始音频落盘到临时目录（ai-helper-asr-last.*）。
         *
         * <p>排查「识别引擎解不了某容器格式」时临时打开；默认关闭——
         * 常开会一直覆写残留文件，最后一名学生的录音长期留在磁盘上。</p>
         */
        private boolean asrDebugDumpEnabled = false;

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
         * whisper 模型档位：`tiny` / `base` / `small` / `medium` / `large-v3-turbo`
         * 也支持量化名（如 `large-v3-turbo-q5_0` / `medium-q5_0`），或直接写 `ggml-xxx.bin`。
         *
         * <p>默认 small：中文答辩场景准确率与速度最均衡，但模型约 487MB；
         * 8G 低功耗机若嫌首次下载慢/识别慢，可改 base（约 142MB，快一倍）。
         * 离线答辩视频建议先用 medium / large-v3-turbo 做基准测试再决定。</p>
         *
         * <p>注意：本档位会参与 {@code WhisperAsrServiceImpl.resolveModel()} 的解析，
         * 不再只是"下载哪个模型"的提示——否则下载了 medium 却仍用 small。</p>
         */
        private String model = "small";

        /**
         * 是否启用 VAD（语音活动检测）：先剔除静音与鼠标/键盘噪声，再交给识别。
         *
         * <p>2026-10-20 实测：2 分钟录屏里静音占 78.6 秒、平均音量 -31dB，
         * 不加 VAD 时鼠标声也会被转成文字。VAD 模型缺失或引擎不支持时自动跳过，
         * 只记日志，不影响原有识别。</p>
         */
        private boolean vadEnabled = true;

        /** VAD 模型路径（留空 = 在引擎同目录/models 下自动查找） */
        private String vadModel = "";

        /** VAD 判定阈值（0~1，越大越严格） */
        private double vadThreshold = 0.5;

        /** VAD 最短语音段（毫秒），过短的片段会被丢弃 */
        private int vadMinSpeechMs = 250;

        /** VAD 最短静音段（毫秒），用于切分语音 */
        private int vadMinSilenceMs = 100;

        /**
         * whisper 推理线程数。0 = 交给引擎自动决定；CPU 核数较少的机器可显式设为 4。
         */
        private int threads = 0;

        /**
         * 转写后处理纠错词表路径（留空 = 使用 classpath 下的 asr-corrections.txt）。
         *
         * <p>格式：每行 `错误写法=正确写法`，`#` 开头为注释。用于修
         * 「文字答辩→文字打变」「部署→剥锁」这类高置信度同音词；只做字面替换，
         * 不做模糊匹配，避免误改正常表达。</p>
         */
        private String correctionsFile = "";

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
         * （实验：全对→全命中，错误/无关→全未命中）。开启后：① 首次生成该题 3~5 条要点清单并缓存；
         * ② 每轮逐条判 命中/未命中/矛盾；③ 命中率低于阈值 → 判"错误"档压缩总分。
         * <b>【N63 · 2026-10-14】覆盖范围已从「仅预设题」放开到全部轮次</b>：追问轮无题库标准答案，
         * 改由 {@code ensureRubricForQuestion} 按题目文本现生成要点（按题目缓存，上限 500）。
         * 注意：命中率分母口径受 3B 输出条数影响，识别「全错」的能力有限（见 CHANGES.md 2026-10-15）。</p>
         *
         * <p>成本：清单生成每题一次；核验每轮一次，增量约 2~5 秒（单轮 ≤20 秒约束内）。关闭即回旧行为。</p>
         */
        private boolean checklistEnabled = true;

        /**
         * 【路线①】命中率低于该值 → 判"错误"档（压分到 checklist-error-final-cap）。
         *
         * <p>【2026-10-10 实测标定 · defenseId=334】全对 3/4=0.75、半对半错 2/3=0.667、夹杂错误 2/3=0.667、全错 0/3=0，
         * 原取 0.70 卡在 0.667 与 0.75 之间。</p>
         *
         * <p><b>【2026-10-14 放宽到 0.50 · defenseId=336 实测回调】</b>0.70 过严：该场预设题 5 轮命中率
         * 0.5/0.333/0.0/0.25，原分 31~38 全被压到 15 分（模型点评还写着"准确、逻辑清晰"），属误杀。
         * 现配合「命中判定放宽」降到 0.50——只有"过半要点都没答到"才压分；完全无关的另有
         * relevant 判定直接归零（不再依赖本阈值）。</p>
         */
        private double checklistErrorHitRateMax = 0.50;

        /** 【路线①】判"错误"档时的最终总分上限（五档表大错×0.5 → 7.5~15，取 15）。 */
        private double checklistErrorFinalCap = 15.0;
    }

    /**
     * 上传报告文档的 AI 评析配置（2026-10-16 · 报告五维评分第一期）。
     *
     * <p>本功能的输入是**学生上传的报告文档**（docx/pptx/xlsx/txt），不是逐轮问答记录，
     * 因此与 {@link Scoring} 那一套判分阈值完全独立，互不影响。</p>
     *
     * <p>全部是经验值，代码默认值即可运行，application.yml 不配置也能跑。</p>
     */
    /**
     * 答辩视频的 AI 评分（2026-10-20）。
     *
     * <p><b>只做听声识别：</b>用户明确视频是答辩现场回答的录音录像，不需要压缩、
     * 不需要画面分析，所以链路是「ffmpeg 抽音轨 → whisper 转写 → 五维点评 → 五维评分」，
     * 全程 CPU，长视频（≤10 分钟）可以整段跑完，不分段。</p>
     */
    @Data
    public static class VideoAnalysis {

        /** 是否启用。默认开启，可一键关掉退回「只有上传没有评分」 */
        private boolean enabled = true;

        /**
         * 【2026-10-28 · V1】是否优先走云端评分（glm-4.7 → glm-4.5-air → deepseek）。
         *
         * <p>默认开启：本地 3B 在口语材料上给分保守（实测 2 分钟真实录像只给 8/50），
         * 云端质量明显更稳。云端全部渠道失败时由 {@code fallbackToLocal} 决定是否回落。</p>
         */
        private boolean cloudEnabled = true;

        /**
         * 云端失败（未配 key / 超时 / 非 2xx / 空正文）时是否回落本地 3B。
         *
         * <p>默认 true：答辩现场断网也不能让功能瘫掉。设为 false 则明确失败，
         * 不静默用本地分——需要「分数必须来自云端」的场合再关。</p>
         */
        private boolean fallbackToLocal = true;

        /**
         * 【2026-10-28】学生上传视频完成后是否**自动提交评分任务**。
         *
         * <p>用户口径：传完后台就开始打分，不用等教师点按钮。默认开启。
         * 评分走异步单线程池（与报告评析共用 core=1），不会阻塞上传响应；
         * 关掉即回到「教师手动触发」的旧行为。</p>
         */
        private boolean autoAnalyzeOnUpload = true;

        /** 转写文本最长保留字数（超出截断，避免 16k 上下文被撑爆；10 分钟口语约 1500~2500 字，正常碰不到） */
        private int maxChars = 20000;

        /** 点评调用 maxTokens（六行，每行 40 字，留足余量） */
        private int commentsMaxTokens = 600;

        /** 评分调用 maxTokens（只输出一行） */
        private int scoreMaxTokens = 100;

        /** 【2026-10-28】综合评价调用 maxTokens（400~700 字，中文约 1 字 1~2 token，留足余量） */
        private int summaryMaxTokens = 1200;

        /**
         * 单场分析总预算（秒）。超时直接失败并把原因写给学生/教师。
         *
         * <p>8G 低功耗机 whisper-small 约 1~3 倍实时：10 分钟音频最坏 30 分钟，
         * 因此默认 1800 秒。压得过低会让长视频必然失败。</p>
         */
        private int budgetSeconds = 1800;

        /** 任务锁 TTL（秒），略大于预算，防止进程崩溃后永久锁死 */
        private int lockTtlSeconds = 2400;

        /** ffmpeg 抽音轨超时（秒）。10 分钟视频抽 16k 单声道 wav 通常几秒内完成 */
        private int ffmpegTimeoutSeconds = 600;

        /**
         * 视频抽音轨时的音频滤镜。
         *
         * <p>2026-10-20 实测：真实录屏平均音量仅 -31dB，直接转写会把鼠标声当成说话。
         * 内置 ffmpeg 是精简构建，<b>没有 loudnorm</b>；改用当前内置 ffmpeg 可用的
         * {@code highpass + dynaudnorm} 组合。留空 = 不做处理。</p>
         */
        private String audioFilter = "highpass=f=80,dynaudnorm=f=150:g=15";

        /** 有效语音不足该秒数时判「素材不足」，不进入评分（防止噪声素材硬出分） */
        private double minSpeechSeconds = 8.0;

        /** 有效语音占视频总时长的最低比例；低于该值同样判「素材不足」 */
        private double minSpeechRatio = 0.05;

        /**
         * 口语场景下「缺失维」的最低分（2026-10-20）。
         *
         * <p>视频是口语，学生大概率讲了但没讲到点上，或 whisper 把术语听错；
         * 按报告口径压到 0~4 会被 3B 打成 0~2 分，实测一场 2 分钟视频直接 4/50「无效」。
         * 这里只把自认缺失的维度抬到该下限，跑题闸门仍在其后执行（跑题照样归零）。</p>
         */
        private double oralMissingFloor = 3.0;

        /**
         * 视频转写专用的 whisper 提示词（不影响 60 秒录音识别）。空串 = 不加提示词。
         *
         * <p><b>2026-10-20 三种提示词实测对比（同一段 2 分 08 秒视频）：</b>
         * <ul>
         *   <li>不加提示词：203 字，被听成「文字打变 / 云打变」；</li>
         *   <li><b>长术语表</b>（把答辩/评分/教师端等词全列上）：**反而更差**——152 字，
         *       句子被切碎，还冒出 {@code (字幕:小小小)} 这种噪声。whisper 的 initial prompt
         *       是"假装前面已经说过这段话"，太长会被当成正文续写；</li>
         *   <li><b>短句</b>（当前默认）：213 字，「教师端」「视频录像」被纠正，
         *       但「文字答辩」仍旧听成「文字打变」。</li>
         * </ul>
         * 结论：短句略优、长表有害，故默认用短句。真要治同音字，靠 whisper 提示词收益有限，
         * 更有效的是换 medium 模型（4 倍算力）或后处理纠错词表。</p>
         */
        private String asrPrompt = "这是一场学生答辩的现场讲解，主要介绍系统功能与实现情况。";

        /**
         * 视频转写专用模型档位（留空 = 跟 {@code app.voice.model} 一致）。
         *
         * <p>2026-10-20 基准：同一段 2:08 视频，`small` 22.0s / 置信度 0.843，
         * `large-v3-turbo-q5_0` 43.2s / 置信度 0.876，领域词略好。视频是离线任务，
         * 可以牺牲一倍时间换准确率；60 秒语音作答仍用 small，避免现场等待过久。</p>
         */
        private String asrModel = "";
    }

    @Data
    public static class ReportDoc {

        /** 是否启用本功能。默认关闭——与 {@code error-verdict-enabled} 同款做法：新链路先能一键回退 */
        private boolean enabled = true;

        // ---------- 文本抽取上限（安全边界，见 DocTextExtractor） ----------

        /** 单个 zip 条目解压后的字节上限（防压缩炸弹） */
        private long maxEntryBytes = 4L * 1024 * 1024;

        /** 全部条目累计解压字节上限 */
        private long maxTotalBytes = 40L * 1024 * 1024;

        /** 压缩比上限，超过即判定为压缩炸弹 */
        private int maxCompressionRatio = 200;

        /** 抽取出的纯文本字数上限，超出截断（不抛异常，避免整场失败） */
        private int maxChars = 60000;

        // ---------- 分级策略（模型只有 16k 上下文） ----------

        /**
         * A 档上限：不超过该字数就直接评（超过才走"分段抽证据"）。
         *
         * <p><b>2026-10-16 实测由 2500 上调到 10000。</b>原值保守了约 8 倍：实测 5520 字材料
         * 仅占 3729 tokens，16k 上下文完全装得下，且**直接评的质量明显好于分段抽证据**——
         * 同一份材料走证据路径时出现「维度错配 / 丢一维 / 把未来展望当创新点」，
         * 直评则是五维全对。中间层「维度|证据点|原文片段」反而把 3B 搞乱了，
         * 而它存在的唯一理由是省上下文，故只在真正超长时才启用。</p>
         *
         * <p>10000 字 ≈ 6800 tokens，加 system 与输出仍在 16k 内且留有余量。</p>
         */
        private int directScoreMaxChars = 10000;

        /** B/C 档的切块字数上限（超过 {@link #directScoreMaxChars} 时按此切块抽证据） */
        private int chunkMaxChars = 2500;

        /** 最多抽多少块证据。超出部分丢弃并记日志——成本闸门，防止超长报告跑几十分钟 */
        private int maxChunks = 8;

        /** 证据抽取单次调用的 maxTokens */
        private int evidenceMaxTokens = 700;

        /** 点评调用（第一次模型调用）的 maxTokens */
        private int finalMaxTokens = 450;

        /**
         * 评分调用（第二次模型调用，只依据点评打分）的 maxTokens。
         *
         * <p>输出只有一行评分行（十几个 token），取 40 是防模型夹带废话——
         * 超长截断会让评分行残缺，解析失败会走重试，方向安全。</p>
         */
        private int scoreMaxTokens = 40;

        /**
         * 综合评价调用（第三次模型调用）的 maxTokens。
         *
         * <p>2026-10-18 新增：五维成绩单偏"逐项打分"，教师还需要一段整体评价。
         * 目标正文约 400~700 字，取 800 留足余量；输出是自然语言，不走严格解析，
         * 失败只记日志、不影响五维成绩单落库。</p>
         */
        private int summaryMaxTokens = 800;

        /** 综合评价落库/展示的字符上限（防模型跑飞；纯自然语言，超长截断不影响评分） */
        private int summaryMaxChars = 2000;

        /** 单个分析任务的总耗时预算（秒），超出即中止并置 FAILED（不写库） */
        private int budgetSeconds = 240;

        // ---------- 文档内图片 OCR（2026-10-20） ----------

        /**
         * 是否识别文档内图片里的文字。
         *
         * <p>模型是纯文本模型，图片只能通过 OCR 变成文字才能参与评判；
         * 关掉后图片内容完全缺席（成绩单会注明），文字正文不受影响。</p>
         */
        private boolean ocrEnabled = true;

        /**
         * 识别引擎：{@code auto}（默认，Windows 自带 OCR 优先）/ {@code windows} / {@code tesseract}。
         *
         * <p>Windows 10/11 自带 OCR 在中文系统上零下载可用；tesseract 需要联网下载安装器
         * （现网官方源与 GitHub raw 均不可达），因此只作可选兜底。</p>
         */
        private String ocrEngine = "auto";

        /**
         * OCR 组件安装目录。留空则自动选第一个存在的盘（如 {@code X:\ocr}）。
         * 与 {@code app.voice.install-dir} 同款逻辑。
         */
        private String ocrInstallDir = "";

        /** 单份报告最多识别多少张图片（成本闸门：每张 1~2 秒） */
        private int ocrMaxImages = 12;

        /** 单张图片字节上限（超出跳过，不报错） */
        private long ocrMaxImageBytes = 2L * 1024 * 1024;

        /** 单张图片的识别超时（秒），超时即跳过该图 */
        private int ocrTimeoutSeconds = 20;

        /** 单份报告整体 OCR 时间预算（秒），用完即停止识别剩余图片 */
        private int ocrTotalBudgetSeconds = 60;

        // ---------- 任务控制 ----------

        /** 单飞锁 TTL（秒）：进程崩了也能自动过期，不会永久锁死 */
        private int lockTtlSeconds = 600;

        /** 状态键 TTL（小时） */
        private int statusTtlHours = 24;

        /** 僵态清理：判定 PROCESSING 超时（秒）；到点仍未完成即置 FAILED */
        private int staleSeconds = 900;

        /** 成果文本落库时是否附带"基于上传文档"的说明前缀 */
        private boolean annotateSource = true;

        /**
         * 是否把抽取出的报告全文落库（{@code defense_records.report_text}）。
         *
         * <p><b>用途：评分依据事后复核</b>——学生申诉时能查出"当时模型收到的是哪份内容"。
         * 此前抽取文本用完即弃（DOC-2 原口径），对一份要当成绩单用的结果来说，
         * 这是可追溯性的短板，2026-10-16 拍板补上。</p>
         *
         * <p>落库时机在**评分之前**：分析失败时恰恰最需要这份证据。</p>
         *
         * <p>存储量级：单场最坏约 180KB（{@link #maxChars} 60000 中文字），
         * 常见报告 3~10KB，千场量级约 3~10MB。注意对应列必须是 MEDIUMTEXT——
         * TEXT 的 64KB 上限会静默截断。</p>
         */
        private boolean storeText = true;
    }

    /**
     * 材料方向判定（预设题覆盖）：判定"这份材料有没有讲本课题要求的内容"。
     *
     * <p><b>与课题无关的可复用组件</b>：判据是 {@code defense_questions} 里的预设题，
     * 不维护任何学科词表，换课题只换数据不换代码。</p>
     *
     * <p><b>口径（用户 2026-10-27）</b>：0 命中才拦（不出分、标待复审）；
     * 命中 1 道及以上交正常五维评分。漏行视为"判不准" → 云端复核，仍不准 → 待复审。</p>
     */
    @Data
    public static class MaterialCoverage {

        /** 总开关。关掉 = 回到"只做资格裁定、不做方向判定"的老行为 */
        private boolean enabled = true;

        /** 送判定的材料字数上限（保头保尾；预设题要的是"有没有讲"，不需要全文） */
        private int maxChars = 6000;

        /** 单次判定的 maxTokens 上限（按题数动态算，这里封顶） */
        private int maxTokens = 400;

        /** 0 命中但有漏行时，是否允许交云端复核（未配置 key 时自动跳过） */
        private boolean cloudReviewEnabled = true;

        /**
         * 【2026-10-27 用户口径】课题没有预设题时是否判「异常（待复审）」。
         *
         * <p>方向判定的唯一依据就是预设题：一道题都没有，就无法判断材料是不是本课题的。
         * 此时**不出分、标待复审**，比"跳过判定直接给分"安全（377/378 就是这么拿到 39 分的）。
         * 设为 false 则退回旧行为（跳过判定）。</p>
         */
        private boolean requirePresetQuestions = true;
    }

    /**
     * 云端 AI 评分配置（2026-10-20）。
     *
     * <p><b>为什么要有这一层</b>：本地 qwen2.5:3b 对五维评分的区分度已经到顶，
     * 而视频/报告只需要把「转写文本 + 声学指标」发给文本大模型，成本极低：
     * GLM-Flash / glm-4.7 有赠送推理包，DeepSeek 一场约一两分钱。</p>
     *
     * <p><b>安全</b>：API key 只从本地 {@code application.yml}（已被 gitignore）
     * 或环境变量读取，绝不写进代码、绝不提交。key 为空时自动退回本地 3B。</p>
     */
    @Data
    public static class Ai {

        private Scoring scoring = new Scoring();

        @Data
        public static class Scoring {
            /** 是否启用云端评分；false = 只用本地 3B */
            private boolean enabled = true;

            /** 首选提供方：glm / deepseek / local */
            private String provider = "glm";

            /** 云端失败后是否回退本地 3B */
            private boolean fallbackToLocal = true;

            /** 采样温度：评分固定 0，保证同一份材料分数稳定 */
            private double temperature = 0.0;

            /**
             * 是否允许模型"思考"（GLM-4.7 等推理模型支持）。
             *
             * <p>评分场景建议关闭：思考会多花 token，且可能让分数漂移；
             * 只有复杂复核才需要打开。</p>
             */
            private boolean thinkingEnabled = false;

            private int connectTimeoutSeconds = 10;

            private int readTimeoutSeconds = 120;

            /** 单次调用最大输出 token（五维点评 + 可选 JSON） */
            private int maxTokens = 1500;

            private Glm glm = new Glm();

            private DeepSeek deepseek = new DeepSeek();

            @Data
            public static class Glm {
                private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

                /** 智谱 API key；留空 = 不启用 GLM */
                private String apiKey = "";

                /** 主模型；用户有 glm-4.7 推理包 */
                private String model = "glm-4.7";

                /** 主模型额度用完/限流时的备用模型（12M tokens 推理包） */
                private String fallbackModel = "glm-4.5-air";
            }

            @Data
            public static class DeepSeek {
                private String baseUrl = "https://api.deepseek.com";

                /** DeepSeek API key；留空 = 跳过 DeepSeek 兜底 */
                private String apiKey = "";

                private String model = "deepseek-v4-flash";
            }
        }
    }
}
