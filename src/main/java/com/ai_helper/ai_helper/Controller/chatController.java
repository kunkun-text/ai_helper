package com.ai_helper.ai_helper.Controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ai_helper.ai_helper.interceptor.AuthInterceptor;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.constant.AiProtocolConstants;
import com.ai_helper.ai_helper.Service.ScorePersistenceService;
import com.ai_helper.ai_helper.mapper.DefenseAnswersMapper;
import com.ai_helper.ai_helper.mapper.DefenseScoreRecordMapper;
import com.ai_helper.ai_helper.mapper.DefenseStudentQuestionsMapper;
import com.ai_helper.ai_helper.pojo.dto.AiAnalysis;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import com.ai_helper.ai_helper.pojo.vo.DefenseResumeVo;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.AiTextUtils;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@Slf4j
public class chatController {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private DefenseTopicsService defenseTopicsService;

    @Autowired
    private DefenseRecordsService defenseRecordsService;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private DefenseAnswersMapper defenseAnswersMapper;

    @Autowired
    private DefenseStudentQuestionsMapper defenseStudentQuestionsMapper;

    @Autowired
    private ScorePersistenceService scorePersistenceService;

    @Autowired
    private DefenseScoreRecordMapper scoreRecordMapper;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String ollamaModelName;

    /** 每轮对话保留的最大消息数（6轮 × 2条 = 12条） */
    private static final int MAX_HISTORY_MESSAGES = 12;

    /** 标准答案截断长度 */
    private static final int ANSWER_TRUNCATE_LENGTH = 50;

    /** 额外问题上限（AI 追问次数，5 预设题 + 5 追问 = 共 10 轮） */
    private static final int EXTRA_QUESTION_LIMIT = 5;

    /** 五维分 key（顺序固定：表达、逻辑、专业、应变、创新），用于服务端统一评分口径 */
    private static final String[] SCORE_KEYS =
            {"expression", "logic", "professional", "adaptability", "innovation"};

    /** 明显回答错误的标注文案（学生端可见） */
    private static final String WRONG_ANSWER_NOTE = "（有明显回答错误）";

    /**
     * 半分档允许的模型自评总分上限（N45 一致性保护，2026-09-27）：模型原总分达到此值，说明它其实认可
     * 这段回答，却仍标了 [错误] —— 模型的"标记"与"评分行"是两套互不校验的输出，两者打架时不再无条件
     * 信标记：只保留点评提示，按模型自己的分数落库，不砍半。仅对"模型自己标 [错误]"生效；复核模型判
     * [错误] 的路径（错误漏判复核）不受影响，否则会把复核机制架空。
     * 触发案例：defenseId=297 第 3 轮，AQI 正确版答案模型自给 36 分（五维 6/8/7/7/8）却标 [错误]，被砍成 18。
     * 经验值，实测可调；调低则保护面变窄（30~31 分的误杀案例会被漏掉）。
     */
    private static final double WRONG_ANSWER_HALF_SCORE_MAX_TOTAL = 32.0;

    // ==================== 判分可信度复核（2026-09-27，实测 defenseId=295 引入） ====================
    // 背景：3B 模型的 [切题]/[错误]/[跑题] 三档标记并不可靠。295 场 10 轮里 [错误] 档 0 次触发，而——
    //   第 3 轮：点评自己写了"存在多个关键错误…AQI等级判断标准有误"，标记却是 [切题]，给 31 分；
    //   第 7 轮：学生自曝 3 处错误，点评"建议用Flink替代MapReduce"，标记 [切题]，给 33 分；
    //   第 2/4 轮：学生答的是上一题内容（答非所问），模型照判 [切题]，给 35/34 分。
    // 此前只有"影子诊断"（命中只打日志不改分）和一条写死在方法里的跑题复核，无法覆盖后两类。
    // 本轮统一为：命中嫌疑信号 → 追加一次复核 → 由复核结果决定是否改判；复核失败一律维持原判定（方向安全）。

    /** 复核类型：决定追加给模型的指令文案，以及调用方的改判方向 */
    private enum RecheckType {
        /** 跑题误判复核（2026-09-25 已有）：模型给 0 分的长回答，复核是否其实切题 → 救回分数 */
        OFF_TOPIC,
        /** 错误漏判复核：模型判 [切题] 但点评带纠错措辞，复核回答是否确有事实性错误 → 走半分档 */
        WRONG_ANSWER,
        /** 答非所问复核：回答与本题概念覆盖率过低，复核是否其实在答本场另一道题 → 归零 */
        OFF_TARGET
    }

    /**
     * 触发错误漏判复核的措辞。模型常用这些词"委婉纠错"，却仍给 [切题] 标记和正常分——
     * 295 场第 7 轮就是命中"替代"的典型（"可以使用Flink…替代MapReduce"）。
     * 只收"纠错/否定"语义的词，不收"建议进一步/建议补充"这类提升性建议，避免每轮都触发复核。
     */
    private static final String[] ERROR_CUE_PHRASES = {
            "说反", "说错了", "并非", "并不是", "有误", "错误", "不正确", "不对", "混淆", "搞错",
            "不可行", "行不通", "不成立", "有偏差", "不准确", "不够准确",
            "改为", "改成", "应改", "替代", "替换", "而不是", "应为", "实际是", "事实上",
            "严格来说", "严格来讲", "用错", "记错", "缺陷", "漏洞", "误导"
    };

    /** 题目概念覆盖率低于该值 → 疑似答非所问，触发 OFF_TARGET 复核（经验值，实测后可微调） */
    private static final double OFF_TARGET_COVERAGE_THRESHOLD = 0.25;

    /** 参与复核的最短回答长度（归一化后）：更短的回答走放弃/敷衍固定零分流程，不占用复核调用 */
    private static final int RECHECK_MIN_ANSWER_LENGTH = 20;

    /**
     * 跨轮重复作答判定阈值（N47 · 2026-10-02）：归一化后与本场此前任一轮答案的二元组 Dice ≥ 该值
     * 即判「复读旧答案」，走固定零分流程、不调评分模型。实测 297 场第 5 轮把第 2 轮约 500 字答案
     * 原样重发（仅个别数字变动）照拿 34 分。阈值取 0.8（防作弊复用同款经验值）：
     * 正常作答即使引用自己此前的表述，主体内容不同，Dice 不会到 0.8；前端"重试"重发同轮答案
     * 时只与本轮已落库行比对为空（N47 只比对**更早轮次**的答案），不受影响。
     */
    private static final double CROSS_ROUND_REPEAT_DICE = 0.8;

    /**
     * 跑题复核救回的最低相关性门槛（N53 · 2026-10-02）：回答与当前题的二元组覆盖率低于该值时，
     * 视为"明显跑题的长回答"，不进入复核救回、维持 0 分。实测 300 场第 6/8 轮：通篇"今天天气不错…
     * 做菜"类的跑题长回答满足「0 分 + ≥20字」复核条件，被复核误救成 28/14 分。
     * 切题回答总会复述题目关键词（覆盖率明显高于该值）；取 0.05 为经验值，300 场第 1 轮被救回的
     * 正确长回答覆盖率远高于此，不受影响。
     */
    private static final double RESCUE_MIN_COVERAGE = 0.05;

    /**
     * 点评"接地"最低命中数（N46/N49 · 2026-10-02）：点评里必须出现至少该数量的「本轮答案」二元组。
     * 实测两连翻车：① 298/299 场点评全是"概念阐述准确、逻辑清晰…"模板话（N49），299 场第 4 轮
     * 点评的是第 5 轮主题（幻觉）；② 2026-10-02 基线实测第 3 轮答 AQI 等级，点评却是第 2 轮的
     * MapReduce 内容。模板话/幻觉点评与本轮答案的二元组交集为 0~1，真实点评引用 1~2 个具体词
     * 即有 3+ 命中，故取 3 为经验阈值。
     */
    private static final int GROUNDED_MIN_HITS = 3;

    /** 追问主题级去重：两道题共享的有效关键词达到该数，视为同主题（N48）。 */
    private static final int FOLLOW_UP_TOPIC_OVERLAP_MIN = 2;

    /** 追问主题级去重：关键词 Jaccard 相似度达到该值，视为同主题（N48）。 */
    private static final double FOLLOW_UP_TOPIC_JACCARD = 0.34;

    /** 追问生成时注入的学生回答截断长度，避免长回答撑爆 prompt。 */
    private static final int FOLLOW_UP_ANSWER_CONTEXT_LENGTH = 180;

    /** 追问生成时注入的题库参考要点截断长度。 */
    private static final int FOLLOW_UP_REFERENCE_LENGTH = 80;

    // ==================== 登录态身份裁决（A1 · 2026-10-05） ====================

    /**
     * 从登录态解析当前用户，并与请求里显式携带的 userId 做一致性校验。
     *
     * <p>答辩链路已纳入 {@code WebConfig.PROTECTED_PATHS}，能进到这里的一定已登录。
     * body/param 里的 {@code userId} 只是兼容字段：<b>一律以登录态 userNumber 为准</b>；
     * 若请求显式携带的 userId 与登录态不一致（伪造他人身份），返回 {@code REJECT} 哨兵，
     * 调用方据此拒绝请求。</p>
     */
    private static final String REJECT = "\u0000__REJECT__";

    private String resolveLoginUser(HttpServletRequest request, String requestUserId) {
        String login = AuthInterceptor.currentUserNumber(request);
        if (login == null || login.isEmpty()) {
            // 理论上拦截器已挡住未登录请求；兜底防御
            return REJECT;
        }
        if (requestUserId != null && !requestUserId.trim().isEmpty()
                && !requestUserId.trim().equals(login)) {
            log.warn("请求携带的 userId({}) 与登录身份({})不一致，已拒绝", requestUserId, login);
            return REJECT;
        }
        return login;
    }

    // ==================== /api/chat 核心接口 ====================

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(@RequestParam(required = false, defaultValue = "") String prompt,
                            @RequestParam(required = false) Integer topicId,
                            @RequestParam(required = false) String sessionId,
                            @RequestParam(required = false) String userId,
                            HttpServletRequest request) {

        // 【A1】身份以登录态为准；伪造他人 userId 直接拒绝
        String loginUser = resolveLoginUser(request, userId);
        if (loginUser == REJECT) {
            return Flux.just("无权访问：登录身份与请求用户不一致，请重新登录后使用自己的账号答辩。");
        }
        userId = loginUser;

        log.info("收到聊天请求 - prompt长度: {}, topicId: {}, sessionId: {}, userId: {}",
                prompt != null ? prompt.length() : 0, topicId, sessionId, userId);

        if (sessionId == null || sessionId.trim().isEmpty()) {
            if (userId != null && !userId.isEmpty()) {
                sessionId = "user_" + userId + "_topic_" + topicId;
            } else {
                sessionId = "topic_" + topicId + "_" + System.currentTimeMillis();
            }
        }

        final String finalSessionId = sessionId;
        final String finalUserInput = prompt != null ? prompt : "";

        if (topicId != null) {
            try {
                Result<Object> topicResult = defenseTopicsService.getTopicById(topicId);

                if (topicResult.getCode() != 1 || topicResult.getData() == null) {
                    log.warn("查询主题信息失败，topicId: {}, code: {}, msg: {}",
                            topicId, topicResult.getCode(), topicResult.getMsg());
                    return handleFallback(userId, topicId, finalUserInput, "未找到相关主题信息", finalSessionId, finalUserInput);
                }

                Result<List<DefenseQuestions>> questionResult = defenseTopicsService.getDefenseQuestionById(topicId);

                List<Integer> existingQuestionIds = new ArrayList<>();
                int existingQuestionCount = 0;

                if (questionResult.getCode() == 1 && questionResult.getData() != null) {
                    List<DefenseQuestions> questions = questionResult.getData();
                    existingQuestionCount = questions.size();

                    for (DefenseQuestions question : questions) {
                        existingQuestionIds.add(question.getQuestionId());
                    }

                    log.info("数据库中已有的问题ID列表: {}, 问题数量: {}", existingQuestionIds, existingQuestionCount);

                    if (!questions.isEmpty()) {
                        log.info("找到 {} 个答辩题目，进入提问模式", questions.size());

                        int extraQuestionLimit = EXTRA_QUESTION_LIMIT;

                        // --- 权威轮次计数（2026-09-15 修复）---
                        // Redis 会话记忆被 trimChatMemory 物理截断到 12 条，旧实现按"历史中的 assistant 消息数"
                        // 计轮次会封顶在 6（实测轮次号 1,2,1,4,5,6,6,6…，收尾判断 followUpDone/hardCapReached 永远无法触发）。
                        // 现改按 defense_score_record 落库行数计数：每次作答（含放弃0分）恰落一行，天然免疫记忆裁剪；
                        // 异步落库极端延迟只会让计数少 1（答辩多走一轮才收尾），方向安全。
                        Integer defenseIdEarly = null;
                        int answeredCount = 0;
                        try {
                            defenseIdEarly = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
                            if (defenseIdEarly != null) {
                                answeredCount = scoreRecordMapper.countByDefenseId(defenseIdEarly);
                            }
                        } catch (Exception countEx) {
                            log.warn("权威轮次计数失败，回退历史消息计数: {}", countEx.getMessage());
                            answeredCount = Math.max(countAssistantMessages(chatMemory.get(finalSessionId)) - 1, 0);
                        }
                        // extraAskedCount 旧值来自 countExtraQuestions（按 DB 追问题条数统计，追问题在两个入库点
                        // 各登记一次而虚高≈2倍），一并纠正为"本轮之前已完成的追问次数"
                        int extraAskedCount = Math.max(answeredCount - existingQuestionCount, 0);
                        // currentRound 保留旧口径 = 本次作答序号（首轮后端出题的 greeting 占 1 条 assistant，故 = 已答次数 + 1）
                        int currentRound = answeredCount + 1;

                        // 追问阶段需要"上一轮 AI 提出的题目"：拼进 prompt 让模型能判断是否切题
                        // （旧实现追问阶段完全不给题目，模型只能闭眼打分）。
                        String lastAskedQuestion = extractQuestionFromLastAiMessage(chatMemory.get(finalSessionId));
                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(), questions, topicId, finalUserInput, userId,
                                extraAskedCount, extraQuestionLimit, currentRound, existingQuestionIds,
                                lastAskedQuestion);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, extraAskedCount, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput, answeredCount));
                    } else {
                        log.info("该题目下暂无问题，进入视频内容辅助回答模式");
                        StringBuilder contextPrompt = buildVideoAssistModePrompt(
                                topicResult.getData(), topicId, finalUserInput);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, 0, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput, 0));
                    }
                } else {
                    log.warn("查询题目信息失败，topicId: {}, code: {}, msg: {}",
                            topicId, questionResult.getCode(), questionResult.getMsg());
                    return handleFallback(userId, topicId, finalUserInput, "未找到相关题目信息", finalSessionId, finalUserInput);
                }

            } catch (Exception e) {
                log.error("查询题目信息时发生异常，topicId: {}", topicId, e);
                return handleFallback(userId, topicId, finalUserInput, "查询题目信息时发生错误", finalSessionId, finalUserInput);
            }
        } else {
            log.info("无 topicId，使用通用模式回答");
            return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, null, finalUserInput, finalSessionId, finalUserInput, 0));
        }
    }

    @PostMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chatPost(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        String prompt = requestBody.getOrDefault("prompt", "").toString();
        Integer topicId = requestBody.get("topicId") != null ?
                Integer.parseInt(requestBody.get("topicId").toString()) : null;
        String sessionId = requestBody.getOrDefault("sessionId", "").toString();
        String userId = requestBody.getOrDefault("userId", "").toString();

        // 【A1】身份以登录态为准；伪造他人 userId 直接拒绝
        String loginUser = resolveLoginUser(request, userId);
        if (loginUser == REJECT) {
            return Flux.just("无权访问：登录身份与请求用户不一致，请重新登录后使用自己的账号答辩。");
        }

        return chat(prompt, topicId, sessionId, loginUser, request);
    }

    // ==================== /api/final-evaluate 总体评价接口 ====================

    @PostMapping("/final-evaluate")
    public Flux<String> finalEvaluate(@RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        Integer topicId = requestBody.get("topicId") != null ?
                Integer.parseInt(requestBody.get("topicId").toString()) : null;
        String userId = requestBody.getOrDefault("userId", "").toString();

        // 【A1】身份以登录态为准；伪造他人 userId 直接拒绝
        userId = resolveLoginUser(request, userId);
        if (userId == REJECT) {
            return Flux.just("无权访问：登录身份与请求用户不一致，请重新登录后使用自己的账号答辩。");
        }

        if (topicId == null || userId == null || userId.isEmpty()) {
            return Flux.just("参数错误：topicId 和 userId 不能为空");
        }

        log.info("生成总体评价 - topicId: {}, userId: {}", topicId, userId);

        Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
        if (defenseId == null) {
            return Flux.just("未找到对应的答辩记录");
        }

        Map<String, Object> aggregated = scorePersistenceService.aggregateScores(defenseId);
        @SuppressWarnings("unchecked")
        List<DefenseScoreRecord> records = (List<DefenseScoreRecord>) aggregated.get("records");

        String evalPrompt = scorePersistenceService.buildFinalEvaluatePrompt(aggregated, records);
        log.info("总体评价 prompt 长度: {} 字符", evalPrompt.length());

        return chatClient.prompt()
                .user(evalPrompt)
                .stream()
                .content()
                .doOnComplete(() -> {
                    log.info("总体评价生成完成 - defenseId: {}", defenseId);
                });
    }

    // ==================== 会话清理 / 答辩进入 ====================

    /**
     * 进入答辩页时调用。
     *
     * <p>【N1 · 2026-09-28】原实现无论是否有未完成作答，一律清会话 + 新建记录 ——
     * 学生答了几题中途退出再进来会从第 1 题重来，原记录永远卡在 pending（孤儿记录）。
     * 现在：存在「已作答但未完成（pending）的记录」时改为<b>续答</b>
     * （不新建记录、把历史回灌会话记忆）；只有「一题未答的空壳」才按原逻辑清理并新建。</p>
     *
     * @return 兼容原有 success / message 字段；新增 resumed / answeredCount / roundNum /
     *         totalRounds / currentQuestion 供前端渲染「继续第 X 题」
     */
    @PostMapping("/chat/clear")
    public Map<String, Object> clearChatMemory(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String sessionId = (String) body.getOrDefault("sessionId", "");
        if (sessionId.isEmpty()) {
            return Map.of("success", false, "message", "sessionId 为空");
        }

        Integer topicId = body.get("topicId") != null ? Integer.parseInt(body.get("topicId").toString()) : null;
        String userId = body.getOrDefault("userId", "").toString();
        // 【A1】身份以登录态为准；伪造他人 userId 直接拒绝
        String loginUser = resolveLoginUser(request, userId);
        if (loginUser == REJECT) {
            return Map.of("success", false, "message", "登录身份与请求用户不一致，请重新登录");
        }
        userId = loginUser;
        // 【2026-09-28 回归修复】默认 false = 从第 1 题开始；只有前端弹窗里用户选了
        // 「继续作答」才传 resume=true（弹窗数据来自 /chat/resume-info）。
        boolean wantResume = Boolean.TRUE.equals(body.get("resume"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("resume")));

        DefenseResumeVo resume = null;
        if (topicId != null && !userId.isEmpty()) {
            try {
                int presetCount = countPresetQuestions(topicId);
                resume = defenseRecordsService.startOrResumeDefenseRecord(
                        topicId, userId, presetCount, presetCount + EXTRA_QUESTION_LIMIT, wantResume);
                if (resume.isResumed()) {
                    restoreChatMemory(sessionId, resume);
                } else {
                    chatMemory.clear(sessionId);
                }
            } catch (Exception e) {
                chatMemory.clear(sessionId);
                log.warn("开启/续答答辩记录失败（已按全新答辩清理会话）: {}", e.getMessage());
            }
        } else {
            chatMemory.clear(sessionId);
        }

        boolean resumed = resume != null && resume.isResumed();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", resumed ? "已恢复未完成的答辩" : "已清理");
        result.put("resumed", resumed);
        result.put("answeredCount", resume != null ? resume.getAnsweredCount() : 0);
        result.put("roundNum", resume != null ? resume.getRoundNum() : 1);
        result.put("totalRounds", resume != null ? resume.getTotalRounds() : 0);
        result.put("currentQuestion", resume != null ? resume.getCurrentQuestion() : null);
        log.info("进入答辩页 - sessionId: {}, 续答: {}, 已答: {} 轮, 当前题: {}",
                sessionId, resumed, result.get("answeredCount"), result.get("currentQuestion"));
        return result;
    }

    /**
     * 只读探测：是否存在「可续答」的答辩场次（<b>不写任何数据</b>）。
     *
     * <p>前端进入答辩页时先调它：返回 {@code resumable=true} 时弹窗问用户
     * 「发现未完成的答辩，是否继续」——选"继续作答"再调 {@code /api/chat/clear}（带 {@code resume=true}）。
     * 这样答辩开端默认一定从第 1 题开始，历史遗留的脏 pending 记录不会再把人直接带进追问阶段。</p>
     */
    @PostMapping("/chat/resume-info")
    public Map<String, Object> resumeInfo(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Integer topicId = body.get("topicId") != null ? Integer.parseInt(body.get("topicId").toString()) : null;
        String userId = body.getOrDefault("userId", "").toString();

        Map<String, Object> result = new HashMap<>();
        result.put("resumable", false);
        // 【A1】身份以登录态为准；伪造他人 userId 视为无可续答记录
        String loginUser = resolveLoginUser(request, userId);
        if (loginUser == REJECT) {
            return result;
        }
        userId = loginUser;
        if (topicId == null || userId.isEmpty()) {
            return result;
        }
        try {
            int presetCount = countPresetQuestions(topicId);
            DefenseResumeVo info = defenseRecordsService.detectResumableDefenseRecord(
                    topicId, userId, presetCount, presetCount + EXTRA_QUESTION_LIMIT);
            result.put("resumable", info.isResumable());
            result.put("answeredCount", info.getAnsweredCount());
            result.put("roundNum", info.getRoundNum());
            result.put("totalRounds", info.getTotalRounds());
            result.put("currentQuestion", info.getCurrentQuestion());
        } catch (Exception e) {
            log.warn("探测可续答场次失败（按全新答辩处理）- topicId: {}, userId: {}: {}",
                    topicId, userId, e.getMessage());
        }
        return result;
    }

    /** 读题库题数（续答时需要判断「已答 N 轮」落在预设题阶段还是追问阶段） */
    private int countPresetQuestions(Integer topicId) {
        try {
            Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
            if (qr.getCode() == 1 && qr.getData() != null) {
                return qr.getData().size();
            }
        } catch (Exception e) {
            log.warn("读取题库题数失败 - topicId: {}", topicId, e);
        }
        return 0;
    }

    /**
     * 续答时把历史回灌到 Redis 会话记忆：格式与正常轮次完全一致
     * （开场白 + 每轮「学生答案 / 考官回复（点评 + 评分 + 下一题）」）。
     *
     * <p>最后一条回复的『下一题:』即当前待答题 —— 下一轮作答时
     * {@code extractQuestionFromLastAiMessage} 取到的就是它，
     * 保证「prompt 里的当前题」与「前端显示的题」同源一致。</p>
     */
    private void restoreChatMemory(String sessionId, DefenseResumeVo resume) {
        try {
            chatMemory.clear(sessionId);
            List<Message> messages = new ArrayList<>();
            List<DefenseResumeVo.Round> rounds = resume.getRounds();

            if (!rounds.isEmpty() && rounds.get(0).getQuestion() != null) {
                // 与首轮后端出题完全一致的开场白格式，前端可解析出题目区块
                messages.add(new AssistantMessage(
                        AiProtocolConstants.COMMENT_TAG + "你好，我是本次答辩的AI考官。请开始作答。\n"
                                + AiProtocolConstants.NEXT_QUESTION_TAG + rounds.get(0).getQuestion()));
            }

            for (int i = 0; i < rounds.size(); i++) {
                DefenseResumeVo.Round r = rounds.get(i);
                if (r.getStudentAnswer() != null && !r.getStudentAnswer().isEmpty()) {
                    messages.add(new UserMessage(r.getStudentAnswer()));
                }
                String nextQuestion = (i + 1 < rounds.size())
                        ? rounds.get(i + 1).getQuestion() : resume.getCurrentQuestion();

                StringBuilder ai = new StringBuilder();
                if (r.getComment() != null && !r.getComment().isEmpty()) {
                    ai.append(AiProtocolConstants.COMMENT_TAG).append(r.getComment()).append("\n");
                }
                if (r.getTotalScore() != null) {
                    ai.append(AiProtocolConstants.SCORE_TAG).append(formatScore(r.getTotalScore())).append("/50|")
                            .append(formatScore(r.getExpression())).append("|")
                            .append(formatScore(r.getLogic())).append("|")
                            .append(formatScore(r.getProfessional())).append("|")
                            .append(formatScore(r.getAdaptability())).append("|")
                            .append(formatScore(r.getInnovation())).append("\n");
                }
                if (nextQuestion != null && !nextQuestion.isEmpty()) {
                    ai.append(AiProtocolConstants.NEXT_QUESTION_TAG).append(nextQuestion);
                }
                if (ai.length() > 0) {
                    messages.add(new AssistantMessage(ai.toString()));
                }
            }

            if (!messages.isEmpty()) {
                chatMemory.add(sessionId, messages);
                trimChatMemory(sessionId);
            }
            log.info("续答回灌会话记忆完成 - defenseId: {}, 历史轮次: {}, 消息数: {}",
                    resume.getDefenseId(), rounds.size(), messages.size());
        } catch (Exception e) {
            log.warn("续答回灌会话记忆失败（不影响轮次计数，作答仍可继续）: {}", e.getMessage());
        }
    }

    /** 分数格式化：整数不带小数位（4.0 → 4），与前端展示口径一致 */
    private String formatScore(Double value) {
        if (value == null) {
            return "0";
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.intValue());
        }
        return String.valueOf(value);
    }

    // ==================== Prompt 构建（极简管道格式） ====================

    @SuppressWarnings("unchecked")
    private StringBuilder buildQuestionModePrompt(Object topicData, List<DefenseQuestions> questions,
                                                   Integer topicId, String prompt, String userId,
                                                   int extraAskedCount, int extraQuestionLimit,
                                                   int currentRound, List<Integer> existingQuestionIds,
                                                   String lastAskedQuestion) {
        boolean isFirstRound = (currentRound == 0);
        int remainingExtra = extraQuestionLimit - extraAskedCount;
        StringBuilder p = new StringBuilder();

        p.append("你是答辩评委，正在对学生进行一对一答辩考核。本场答辩共约10轮：5道预设题 + 至多5次AI追问。除首轮外，你必须严格按照以下三行格式输出，顺序不可颠倒，每行以固定标签开头，不要任何多余内容：\n");
        p.append("点评:（40字以内，必须以 [切题]、[错误]、[跑题] 三者之一开头，三者只能选一个；且必须引用学生本轮回答里的 1~2 个具体内容（技术名词、步骤或数据），不能写成放在任何回答上都成立的空话。[切题]后再一句话肯定优点、指出一条改进建议；[错误]时先一句话点明具体错在哪里（不要写“表述不够清晰”这类套话）；[跑题]时只说明回答与本题无关，严禁虚构“思路清晰”“回答完整”等与实际不符的肯定，禁止空话套话）\n");
        p.append("评分:总分/50|表达分|逻辑分|专业分|应变分|创新分（各0-10整数，五维分数相加必须等于总分）\n");
        p.append("下一题:（30字以内，提问下一道题目）\n");
        p.append("示例1（切题，回答有内容）：\n点评:[切题]概念阐述准确、逻辑清晰，但缺少实际案例支撑，建议结合具体业务场景补充说明。\n评分:38/50|8|7|8|7|8\n下一题:请解释HDFS中NameNode的作用？\n\n");
        p.append("示例2（跑题，回答与本题无关）：\n点评:[跑题]回答内容与本题无关，未正面回应所问内容。\n评分:0/50|0|0|0|0|0\n下一题:请解释HDFS中NameNode的作用？\n\n");
        p.append("示例3（错误，回答了本题但内容有明显错误）：\n点评:[错误]把小文件与大文件的读写机制说反了，小文件反而是HDFS的负担，加磁盘并不能消除该问题。（有明显回答错误）\n评分:30/50|6|6|6|6|6\n下一题:请解释HDFS中NameNode的作用？\n\n");
        p.append("【轮次铁律】5道预设题未全部答完前，必须逐题输出『下一题:』提问下一道预设题；预设题答完后，最多允许5次AI追问，追问阶段每轮仍输出『下一题:』（30字以内）由你自拟追问。只有『预设题全部答完且追问已达5次』时，最后一行才允许输出『总结:』。任何情况下严禁提前输出『总结:』或提前结束答辩；只要还剩预设题或追问额度，最后一行必须输出『下一题:』，严禁输出『总结:』。每轮末尾会附带 [进度: 第X题/共Y题, 已追问Z/5次]，请据此判断当前进度并输出正确标签。\n\n");
        p.append("【判分第一步·先判是否切题】拿学生这段回答去对照上面给出的【当前题】，只有当【整段回答】完全未正面回应本题所问时才判为跑题（例如问“如何判断AQI等级”，却通篇只讲HBase如何存储数据），点评必须以[跑题]开头，评分固定为 0/50|0|0|0|0|0。以下情形严禁判跑题：① 回答使用了Markdown加粗、编号、列表等排版格式；② 回答主体回应了本题，只是夹带口头语、自嘲或“这道题我不会”之类附带语句；③ 回答包含与本题相关的具体概念、步骤、事实或方法（哪怕不完整）。回答正面回应了本题、且包含与本题相关的具体事实/步骤/数据的，必须判为切题，点评以[切题]开头。\n");
        p.append("【判分第二步·切题才给分】正确完整、条理清晰 = 35~50；基本正确但不完整 = 20~34；有明显错误或关键缺漏 = 5~19；答非所问、含糊其辞、“不知道” = 0。严禁凭印象乱给高分。\n");
        p.append("【判分第三步·三档标记怎么选】每轮点评必须且只能用 [切题]、[错误]、[跑题] 三者之一开头：\n");
        p.append("① [跑题]：整段回答完全没回应本题所问的内容（例：问“如何判断AQI等级”，却通篇讲HBase如何存储）。只要回答谈到了本题所问的主题，就【严禁】判[跑题]。\n");
        p.append("② [错误]：回答确实在回应本题，但内容存在明显错误——关键概念说反、方法用错、事实或数据错误、结论错误、把无关技术硬套本题。此时点评要先一句话点出【具体错在哪里】（例如“把小文件读写机制说反了”），不要写“表述不够清晰”这类套话，并在点评末尾附上“（有明显回答错误）”。\n");
        p.append("③ [切题]：回答正确、与本题相关。\n");
        p.append("硬性要求：内容明显错误的回答必须判[错误]——既【严禁】用[切题]给它正常分，也【严禁】用[跑题]顶替。\n\n");
        p.append("【判分第四步·软性纠错同样算错误】下面这些措辞说明你已经发现回答有问题，此时必须判[错误]："
                + "“建议改为/改成/应改为”、“用X替代/替换Y”、“应为/实际是/事实上”、“说反了/有误/不准确/不正确/不成立/有偏差”、"
                + "“混淆了/用错了/不可行/行不通”。【严禁】一边在点评里纠正学生、一边仍判[切题]给正常分——"
                + "这是自相矛盾，实测已多次发生（题目问实时监控方案，学生答的“用MapReduce每分钟启动一次任务”根本做不到秒级响应，"
                + "点评写了“可以用Flink替代MapReduce”，标记却是[切题]，给了33分）。口诀：点评里出现“应该换成别的做法”＝现在的做法是错的＝[错误]。\n\n");
        p.append("【判分第五步·答的是另一道题才算跑题（从严认定，不得滥用）】只有当学生整段回答都在讲一个"
                + "与【当前题】主题完全不同、且不属于同一技术领域的内容时，才判[跑题]"
                + "（例如题目问“如何用MapReduce统计PM2.5”，学生整段讲“食堂饭菜贵不贵”；"
                + "或题目问“HDFS小文件性能差异”，学生整段讲“AQI等级判断与天数统计”）。\n");
        p.append("【严禁滥用第五步】以下情形一律判[切题]，严禁判[跑题]："
                + "① 回答与本题同属一个技术领域或直接相关——题目问“Hadoop生态核心组件”就回答Hadoop组件＝切题，"
                + "题目问“HDFS小文件”就回答HDFS架构＝切题，题目问“MapReduce统计PM2.5”就回答MapReduce原理＝切题；"
                + "② 回答只是没有逐字复述题目、切入角度与标准答案不同、答得偏浅或不完整；"
                + "③ 你无法确认学生究竟在答哪一道题——此时一律按[切题]处理并按内容给分。\n");
        p.append("口诀：拿不准就判[切题]。误判[跑题]会把正确回答直接打成0分，代价远大于漏判；"
                + "本条只在【主题完全不同】时才用，不要因为回答里出现了别的名词就判跑题。\n\n");
        p.append("示例4（答非所问，答的是本场另一道题）：\n点评:[跑题]回答的是“Hadoop生态系统核心组件”，与本题所问的MapReduce统计PM2.5无关，未正面回应本题。\n评分:0/50|0|0|0|0|0\n下一题:请说明HDFS中小文件问题的成因？\n\n");
        p.append("【点评铁律·只评本轮回答】点评的唯一对象是学生【本轮回答】本身：\n"
                + "① 必须从本轮回答里挑出 1~2 个具体内容写进点评（技术名词、步骤或数据，例如“你提到的Map阶段逐行解析”），而不是写成放在任何回答上都成立的空话；\n"
                + "② 【严禁】点评本场其他轮次的题目内容——实测已发生轮次串台：题目问 AQI 等级、学生答的正是 AQI 判定标准，点评却写“把大文件与小文件的读写机制说反了”，那是下一轮的主题；\n"
                + "③ 如果你在本轮回答里找不到可引用的具体内容，说明你没有在读本轮回答，请重读后再评。\n\n");
        p.append("特别注意：学生【整段回答】就是“不知道/不会/不清楚”类短语，或整段回答无实质内容（如“额”“嗯”“开始”“一般吧”“还好吧”“差不多”“1”“666”“对对对”“你是对的”“我是对的”“感觉不太行”“我会这道题”等语气词、敷衍输入、只声称会/不会但未实际回答、纯数字、报数玩笑（如“我是250”）、纯标点、骂人）时，本题五维必须全部给0分，点评以[跑题]开头并写明回答无实质内容；之后必须照常输出『下一题:』继续提问，严禁因此输出『总结:』或提前结束答辩，除非本轮提示明确说明这是最后一轮。【长度门槛】上述零分规则只看整段回答本身：若回答较长（超过50字）且包含与本题相关的实质内容，即使其中夹带上述口头语或玩笑语句，也必须按实质内容正常评分，严禁整段判0分。【防作弊】若学生回答与本场答辩中任意一道已问过的题目（含【当前题】及之前的预设题、追问题）原文高度重复（把题目复制粘贴当回答），或回答内容只是要求/抱怨给分（如“给我满分”“为什么给我0分”），本题五维必须全部给0分，点评以[跑题]开头并写明未正面作答。\n\n");

        if (isFirstRound) {
            p.append("共").append(questions.size()).append("题:\n");
            for (int i = 0; i < questions.size(); i++) {
                DefenseQuestions q = questions.get(i);
                p.append(i + 1).append(". ").append(q.getQuestion());
                if (q.getStandardAnswer() != null && !q.getStandardAnswer().isEmpty()) {
                    p.append(" (要点:").append(truncateSummary(q.getStandardAnswer(), ANSWER_TRUNCATE_LENGTH)).append(")");
                }
                p.append("\n");
            }
            p.append("\n首轮：用自然语言向学生问好，然后完整提出第1题，不要使用评分格式。\n");
        } else {
            int qi = currentRound - 1;
            if (qi >= 0 && qi < questions.size()) {
                DefenseQuestions cq = questions.get(qi);
                p.append("当前题:").append(cq.getQuestion());
                if (cq.getStandardAnswer() != null && !cq.getStandardAnswer().isEmpty()) {
                    p.append(" (参考:").append(truncateSummary(cq.getStandardAnswer(), 100)).append(")");
                }
                p.append("\n");
                int ni = qi + 1;
                if (ni < questions.size()) {
                    p.append("下题:").append(questions.get(ni).getQuestion()).append("\n");
                } else if (remainingExtra > 0) {
                    p.append("已无预设题,可追问").append(remainingExtra).append("个后总结\n");
                } else {
                    p.append("本题为整场答辩的最后一轮。学生回答完后请给出总结。注意：本轮不要输出『下一题』，最后一行改为：总结:（100字以内，对整场答辩的总体评价，先肯定优点，再指出整体不足和建议）\n");
                }
            } else {
                // 预设题已问完的追问阶段：明确剩余额度，额度用完则要求本轮总结。
                // 【关键】必须把"上一轮提出的追问"作为【当前题】显式给出：
                // 旧实现此分支完全不给题目，模型手里只有答案、没有题目，只能闭眼打分 ——
                // 实测同一段与题目无关的 513 字文字连续 6 轮拿到 28~36 分（满分 50）。
                boolean hasLastAsked = lastAskedQuestion != null && !lastAskedQuestion.trim().isEmpty();
                p.append("当前题:")
                        .append(hasLastAsked ? lastAskedQuestion.trim() : "即你上一轮提出的那道追问（见对话历史）")
                        .append("\n");
                if (remainingExtra > 0) {
                    p.append("预设题已全部问完，还可追问").append(remainingExtra).append("个。请按格式输出点评/评分/下一题，“下一题”由你提出。\n");
                } else {
                    p.append("预设题与追问已全部问完，这是最后一轮。请按格式输出点评/评分，最后一行改为：总结:（100字以内，对整场答辩的总体评价，先肯定优点，再指出整体不足和建议）。不要输出『下一题』。\n");
                }
            }
        }

        if (prompt != null && !prompt.trim().isEmpty()) {
            p.append("\n学生答:").append(prompt).append("\n");
        }

        // --- 动态段末尾进度说明（结构化标签由 sendMessageWithMemory 统一附加） ---
        int progressX = currentRound + 1;
        p.append("\n当前进度：第").append(progressX)
                .append("题/共").append(questions.size() + EXTRA_QUESTION_LIMIT)
                .append("题，已追问").append(extraAskedCount)
                .append("/").append(EXTRA_QUESTION_LIMIT)
                .append("次，请严格据此决定本轮最后一行输出『下一题:』还是『总结:』，追问阶段同样用『下一题:』标签。\n");

        return p;
    }

    // ==================== 消息发送与记忆管理 ====================

    private String sendMessageWithMemory(List<Integer> existingQuestionIds, int existingQuestionCount,
                                                int extraAskedCount, String userId, Integer topicId, String fullPrompt,
                                                String sessionId, String userInput, int answeredCount) {
        List<Message> history = chatMemory.get(sessionId);

        // --- 首轮出题：直接由题库提供，不调用模型（零延迟、无答案泄露、题目完整） ---
        if ((userInput == null || userInput.trim().isEmpty()) && topicId != null) {
            try {
                Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
                if (qr.getCode() == 1 && qr.getData() != null && !qr.getData().isEmpty()) {
                    String firstQuestion = qr.getData().get(0).getQuestion();
                    // 与常规轮次统一为三段式（点评:/下一题:），前端才能解析出题目区块并正确计数；
                    // 旧纯文本开场白导致前端渲染成普通气泡、且不计数使下一轮撞号"第1题"（2026.9.15 修复）
                    // 标签用协议常量，与续答回灌（restoreChatMemory）的拼法严格一致，避免两处字面量漂移
                    String greeting = AiProtocolConstants.COMMENT_TAG + "你好，我是本次答辩的AI考官。请开始作答。\n"
                            + AiProtocolConstants.NEXT_QUESTION_TAG + firstQuestion;
                    chatMemory.add(sessionId, List.of(new AssistantMessage(greeting)));
                    trimChatMemory(sessionId);
                    log.info("首轮由后端直接出题: {}", firstQuestion);
                    return greeting;
                }
            } catch (Exception e) {
                log.warn("首轮后端出题失败，回退模型出题", e);
            }
        }

        // --- 学生放弃作答（"不知道/不会"类短语）、敷衍作答（"开始/1/一般"等无实质内容，2026-09-15）
        //     或【直接复制题目原文作答】（N11 防作弊，2026-09-25）：不调用评分模型，本题零分并直接进入下一题 ---
        // 复制题目必须在此拦截：实测（defenseId=289）把 5 道预设题原文逐字粘贴当回答，模型全判 [切题]
        // 并给出 32~36 分（"空气如何用MapReduce统计"这句题目本身被当成答对了）。此时模型不认为自己被"跑题"
        // 触发，只能靠这道前置闸门——判定在调用模型之前完成，不依赖模型自觉。
        // 2026-09-26：比对范围由「仅当前题」扩展为「本场任意已问题目」，防止学生复制"别的题"的题目当回答仍拿分。
        boolean copiedFromQuestion = isCopiedQuestion(userInput, topicId, answeredCount, existingQuestionIds, history);
        boolean pleadForScore = isPleadForScore(userInput);
        // 【N47 · 2026-10-02】跨轮复读检测：与本场此前轮次的答案高度相似（Dice ≥ 0.8）→ 固定零分。
        // 判定在调用模型之前完成（与复制题目同思路，不依赖模型自觉）；只比对更早轮次，前端重试不受影响。
        boolean repeatOfPriorAnswer = isRepeatOfPriorRoundAnswer(userInput, topicId, userId, answeredCount);
        if (topicId != null && userId != null
                && (isGiveUpAnswer(userInput) || isJunkAnswer(userInput) || copiedFromQuestion || pleadForScore
                || repeatOfPriorAnswer)) {
            // 点评文案按判定来源区分：复制题目 / 讨分 / 放弃作答 / 敷衍作答 / 复读旧答案
            String zeroComment;
            if (copiedFromQuestion) {
                zeroComment = "检测到直接复制题目内容作答，本题计0分。请结合自己的理解，用自己的话作答。";
            } else if (pleadForScore) {
                zeroComment = "学生未正面作答，而是抱怨/要求给分，本题计0分。答辩成绩依据回答内容评定，请认真答题。";
            } else if (repeatOfPriorAnswer) {
                zeroComment = "检测到本题回答与本场此前的回答高度重复，本题计0分。请针对本题给出自己的回答。";
            } else if (isGiveUpAnswer(userInput)) {
                zeroComment = "学生表示不知道该题，本题计0分，建议课后补强该知识点。";
            } else {
                zeroComment = "学生未给出实质回答，本题计0分，建议结合问题认真作答。";
            }
            String fixedResponse = handleGiveUpAnswer(existingQuestionIds, existingQuestionCount,
                    userId, topicId, userInput, sessionId, history, answeredCount, zeroComment);
            if (fixedResponse != null) {
                return fixedResponse;
            }
            // 返回 null 表示轮次/题库异常，回退正常模型流程
        }

        log.info("=== 当前使用的 Ollama 模型: {} ===", ollamaModelName);

        // --- 历史消息裁剪：只保留最近 MAX_HISTORY_MESSAGES 条 ---
        List<Message> trimmedHistory;
        if (history.size() > MAX_HISTORY_MESSAGES) {
            trimmedHistory = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size()));
            log.info("历史消息裁剪 - 原始: {} 条, 保留最近: {} 条", history.size(), trimmedHistory.size());
        } else {
            trimmedHistory = new ArrayList<>(history);
        }

        // --- 精简日志 ---
        log.debug("sessionId: {}, 历史消息数: {}", sessionId, trimmedHistory.size());

        // --- 构建完整 prompt（只拼接最近6轮历史摘要） ---
        StringBuilder completePrompt = new StringBuilder();

        if (!trimmedHistory.isEmpty()) {
            completePrompt.append("【对话摘要】\n");
            for (Message msg : trimmedHistory) {
                if (msg instanceof UserMessage userMsg) {
                    String brief = userMsg.getText().length() > 100
                            ? userMsg.getText().substring(0, 100) + "..."
                            : userMsg.getText();
                    completePrompt.append("学生：").append(brief).append("\n");
                } else if (msg instanceof AssistantMessage asstMsg) {
                    // 只提取AI回复中的核心判断，不携带完整评分内容
                    String brief = extractBriefFromAiMessage(asstMsg.getText());
                    completePrompt.append("考官：").append(brief).append("\n");
                }
            }
            completePrompt.append("\n");
        }

        completePrompt.append(fullPrompt);

        // --- 进度标签：附加到用户消息末尾，让模型无需回看历史也知道当前轮次（10 轮改造） ---
        // 权威口径（2026-09-15 修复）：进度行序号与 buildQuestionModePrompt 保持一致 = 下一题序号（已答次数 + 2）
        if (topicId != null) {
            int progressTotal = existingQuestionCount + EXTRA_QUESTION_LIMIT;
            int progressX = Math.min(Math.max(answeredCount + 2, 1), progressTotal);
            completePrompt.append("\n[进度: 第").append(progressX)
                    .append("题/共").append(progressTotal)
                    .append("题, 已追问").append(extraAskedCount)
                    .append("/").append(EXTRA_QUESTION_LIMIT).append("次]");
        }

        // --- 阻塞调用模型（强制 maxTokens + temperature=0） ---
        // temperature=0：同一份回答的评分必须可复现。实测同一段答案在不同题目下得 32/28/36/34（±4 分），
        // 属于采样随机性导致的评分不稳（待办 N7）。
        String aiResponse = chatClient.prompt()
                .user(completePrompt.toString())
                .options(OpenAiChatOptions.builder()
                        .model(ollamaModelName)
                        .temperature(0.0)
                        .maxTokens(320)
                        .build())
                .call()
                .content();
        log.info("=== AI 原始返回内容: {}", aiResponse);

        // 还未到最后一轮时，剥离模型提前输出的"总结"行，防止前端误判答辩提前结束
        aiResponse = stripPrematureSummary(aiResponse, existingQuestionCount, extraAskedCount);

        try {
            if (topicId != null && userId != null) {
                // 轮次口径（2026-09-15 修复）：权威计数 = 评分落库行数 + 1（answeredCount 由 chat() 传入）。
                // 旧实现按"历史中的 assistant 消息数"计数，Redis 记忆被 trimChatMemory 物理截断到 12 条后
                // 封顶在 6 —— 实测轮次号 1,2,1,4,5,6,6,6…、收尾判断 followUpDone/hardCapReached 永远无法触发，
                // 防死循环兜底形同虚设。评分表每次作答（含放弃0分）恰落一行，天然免疫记忆裁剪；
                // 异步落库极端延迟只会让计数少 1（答辩多走一轮才收尾），方向安全。
                int assistantCountInHistory = answeredCount + 1;

                if (userInput != null && !userInput.trim().isEmpty()) {
                    Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
                    int currentRoundNum = assistantCountInHistory;

                    Map<String, Object> scores = scorePersistenceService.parseScoresFromResponse(aiResponse);
                    String comment = (String) scores.getOrDefault("comment",
                            extractFeedbackFromResponse(aiResponse));

                    // 【切题判定兜底 · 2026-09-17】点评以 [跑题] 开头时，无条件把五维分数强制归零。
                    // 为什么不依赖模型自觉：提示词里早就写了"答非所问必须给低分"，但实测 3B 模型对一段
                    // 与题目无关的 513 字文字仍连续给出 28~36 分。于是改为——让模型只回答"切题/跑题"这个
                    // 二选一（它做得到），最终分值由服务端按判定结果决定（确定性，不留给模型发挥）。
                    // 【N42 收口 · 2026-10-08】本段触发条件由「点评/回复带 [跑题] 标记」放宽为
                    // 「带 [跑题] 标记」或「模型自评分 ≤ 0」。实测存在模型直接给 0 分却不写 [跑题] 的情况：
                    // 旧实现整段跳过，这类 0 分既不会被服务端归零盖章，也没有任何救回通道（N42 残留缺口）。
                    // 是否真的强制归零仍由 offTopicMarked 决定（未带标记时只尝试救回、不改写点评文案）；
                    // N53（覆盖率门槛）、N54、N11（复制题）、抱怨/要分 等排除闸门一律保持不变。
                    boolean offTopicMarked = isOffTopicMarked(comment) || isOffTopicMarkedInResponse(aiResponse);
                    Object gateTotalObj = scores.get("totalScore");
                    double gateTotal = (gateTotalObj instanceof Number nGate) ? nGate.doubleValue() : 0.0;
                    boolean zeroScoreNeedsRescue = gateTotal <= 0
                            && !isJunkAnswer(userInput) && !isGiveUpAnswer(userInput);
                    if (offTopicMarked || zeroScoreNeedsRescue) {
                        // 【跑题误判复核 · 2026-09-25】实测（defenseId=288 第5轮）实质切题的长回答因夹带口头语被
                        // 误判[跑题]0分（284 场第8轮同款）。模型自己给的 0 分，服务端强制归零只是盖章——
                        // 长回答（归一化后≥20字）被判 0 时，追加纠正指令重评一次，重评仍未切题才维持 0 分。
                        // 防作弊闸门（N11 同源）：回答与本场任意已问题目原文高度重复 → 属复制粘贴，不复核，维持 0 分。
                        boolean confirmedOff = offTopicMarked;
                        Object tObj = scores.get("totalScore");
                        double modelTotal = (tObj instanceof Number n) ? n.doubleValue() : 0.0;
                        String normAnswer = normalizeAnswerForJudge(userInput);
                        if (modelTotal <= 0 && normAnswer.length() >= 20) {
                            boolean copiedQuestion = isCopiedQuestion(userInput, topicId, assistantCountInHistory - 1,
                                    existingQuestionIds, trimmedHistory);
                            if (copiedQuestion) {
                                log.info("跑题复核跳过：回答为复制题目原文，维持0分 - defenseId: {}", defenseId);
                            } else if (isComplainOrPlead(userInput)) {
                                // 【复核排除名单 · 2026-09-27】实测 defenseId 294 第6轮：纯抱怨被判 0 后被复核
                                // 救成 25 分（越闹分越高），且重评时模型把抱怨当成上一题的作答，语义完全错位。
                                log.info("跑题复核跳过：回答为抱怨/要分，维持0分 - defenseId: {}", defenseId);
                            } else if (isClearlyOffTarget(userInput, topicId, assistantCountInHistory - 1,
                                    existingQuestionIds, trimmedHistory)) {
                                // 【N53 · 2026-10-02】救回门槛：回答与当前题二元组覆盖率≈0 → 明显跑题的长回答，
                                // 不再送复核救回（300 场第 6/8 轮"今天天气不错…做菜"被复核误救成 28/14 分）。
                                // 切题回答总会复述题目关键词，覆盖率远高于门槛，救回通道不受影响。
                                log.info("跑题复核跳过：回答与当前题几乎零相关（覆盖率低于 {}），明显跑题不予救回 - defenseId: {}",
                                        RESCUE_MIN_COVERAGE, defenseId);
                            } else {
                                String rescored = rescoreSuspectedMisjudge(completePrompt, topicId,
                                        assistantCountInHistory - 1, existingQuestionIds, trimmedHistory, userInput);
                                Map<String, Object> rescores = (rescored == null)
                                        ? null : scorePersistenceService.parseScoresFromResponse(rescored);
                                double newTotal = 0;
                                if (rescores != null) {
                                    Object rtObj = rescores.get("totalScore");
                                    newTotal = (rtObj instanceof Number n2) ? n2.doubleValue() : 0.0;
                                }
                                if (rescored != null && newTotal > 0 && !rescores.isEmpty()
                                        && !isOffTopicMarkedInResponse(rescored)) {
                                    log.info("跑题误判复核改判切题 - defenseId: {}, 复核总分: {} (原0分)",
                                            defenseId, newTotal);
                                    scores = rescores;
                                    aiResponse = stripPrematureSummary(rescored, existingQuestionCount, extraAskedCount);
                                    comment = (String) rescores.getOrDefault("comment",
                                            extractFeedbackFromResponse(rescored));
                                    String strippedRescored = stripTopicMarker(comment);
                                    comment = (strippedRescored == null || strippedRescored.isEmpty())
                                            ? comment : strippedRescored;
                                    confirmedOff = false;
                                } else {
                                    // 【复核日志补全 · 2026-09-27】无论改判还是维持都留痕，便于排查"复核了但没改"
                                    log.info("跑题误判复核维持0分 - defenseId: {}, 原因: {}", defenseId,
                                            rescored == null ? "重评调用失败" : "重评仍判跑题或无有效分");
                                }
                            }
                        }
                        if (confirmedOff) {
                        log.info("切题判定为跑题，强制五维归零 - defenseId: {}, 模型原总分: {}",
                                defenseId, scores.get("totalScore"));
                        scores = applyZeroScore(scores);
                        // 同步改写返回给前端的评分行，避免"气泡里显示 35 分、库中却是 0 分"
                        aiResponse = forceZeroScoreLine(aiResponse);
                        String stripped = stripTopicMarker(comment);
                        comment = (stripped == null || stripped.isEmpty())
                                ? "回答内容与本题无关，未正面回应所问内容。"
                                : stripped;
                        }
                    } else if (isWrongAnswerMarked(comment) || isWrongAnswerMarkedInResponse(aiResponse)) {
                        // 【明显回答错误·半分档 · 2026-09-26】实测（defenseId 292 第4轮）：学生把 HDFS 小文件机制
                        // 说反、结论全错，模型仍判 [切题] 给 35 分（正确答案档）。"答了本题但内容明显错误"统一按
                        // 半分计分并在点评里标注，避免错答拿到正常分。分值由服务端决定，不留给模型自行减半，避免双重折扣。
                        Object wrongTotalObj = scores.get("totalScore");
                        double wrongModelTotal = (wrongTotalObj instanceof Number n) ? n.doubleValue() : 0.0;
                        String strippedWrong = stripTopicMarker(comment);
                        String wrongBase = (strippedWrong == null || strippedWrong.isEmpty())
                                ? "回答针对本题，但存在明显错误。" : strippedWrong;
                        if (wrongModelTotal >= WRONG_ANSWER_HALF_SCORE_MAX_TOTAL) {
                            // 【N45 一致性保护 · 2026-09-27】见常量注释：模型自评已达正常档却仍标 [错误]，
                            // 判定标记不可信 → 只保留点评里的错误提示，分数按模型自己的给法落库。
                            log.info("检测到[错误]标记但模型自评达正常档({})，判定标记不可信，不打折 - defenseId: {}, 原总分: {}",
                                    WRONG_ANSWER_HALF_SCORE_MAX_TOTAL, defenseId, wrongModelTotal);
                            comment = wrongBase.contains(WRONG_ANSWER_NOTE)
                                    ? wrongBase : wrongBase + WRONG_ANSWER_NOTE;
                        } else {
                            log.info("检测到明显回答错误，按半分计 - defenseId: {}, 原总分: {}",
                                    defenseId, wrongModelTotal);
                            scores = applyWrongAnswerHalfScore(scores);
                            comment = wrongBase.contains(WRONG_ANSWER_NOTE)
                                    ? wrongBase : wrongBase + WRONG_ANSWER_NOTE;
                        }
                    } else {
                        // 非跑题：去掉点评开头的 [切题] 标记，只把正文给用户看
                        comment = stripTopicMarker(comment);

                        // 【N51 · 2026-10-02】复核优先级调整：答非所问的「硬信号」（覆盖率）优先于措辞词表。
                        // 原实现先跑"疑似错误"复核并置位 recheckDone → 298 场第 2 轮明显答非所问
                        // （问 MapReduce 统计、答 NameNode）被错误漏判复核"判切题"短路，漏网拿 38 分。
                        // 现在硬信号先复核；复核判切题时仍允许继续走错误漏判复核（宁可多一次调用不漏判）。

                        // 【答非所问复核 · 2026-09-27】295 场第 2/4 轮：题目问"如何用MapReduce统计PM2.5"、
                        // "HDFS小文件性能差异"，学生整段答的是上一题（Hadoop生态组件 / AQI等级统计），
                        // 模型照判 [切题] 给 35/34 分。嫌疑信号 = 回答里几乎不含本题的核心概念
                        // （题目二元组覆盖率过低）；是否真的答非所问仍交给模型复核判定，服务端只负责归零。
                        boolean recheckDone = false;
                        String currentQuestion = (defenseId != null)
                                ? getQuestionTextForRound(topicId, assistantCountInHistory - 1,
                                        existingQuestionIds, trimmedHistory)
                                : null;
                        if (defenseId != null && currentQuestion != null && !currentQuestion.isEmpty()
                                && normalizeAnswerForJudge(userInput).length() >= RECHECK_MIN_ANSWER_LENGTH
                                && questionCoverage(currentQuestion, userInput) < OFF_TARGET_COVERAGE_THRESHOLD) {
                            String rescored = runRecheck(completePrompt, RecheckType.OFF_TARGET,
                                    currentQuestion, userInput);
                            if (rescored != null && isOffTopicMarkedInResponse(rescored)) {
                                scores = applyZeroScore(scores);
                                aiResponse = forceZeroScoreLine(aiResponse);
                                comment = "回答内容与本题无关（疑似在回答本场另一道题），未正面回应所问内容。";
                                log.warn("答非所问复核命中，归零 - defenseId: {}, 当前题: {}", defenseId, currentQuestion);
                                recheckDone = true;
                            } else {
                                log.info("答非所问复核维持原分 - defenseId: {}, 当前题: {}, 覆盖率低于阈值但复核判切题",
                                        defenseId, currentQuestion);
                            }
                        }

                        // 【错误漏判复核 · 2026-09-27】此前这里只是"影子诊断"（命中只打 WARN、不改分）。
                        // 295 场实测证明不改分不行：第 3 轮点评自己写了"存在多个关键错误、AQI等级判断标准有误"，
                        // 标记却是 [切题]，给了 31 分。改为：命中嫌疑措辞 → 追加一次正确性复核 →
                        // 复核确实判 [错误] 才按半分档计（A2 方案，宁可多一次调用也不误杀正常回答）。
                        if (!recheckDone && defenseId != null && containsErrorCue(comment)) {
                            String rescored = runRecheck(completePrompt, RecheckType.WRONG_ANSWER,
                                    currentQuestion, userInput);
                            if (rescored != null && isWrongAnswerMarkedInResponse(rescored)) {
                                scores = applyWrongAnswerHalfScore(scores);
                                Object rc = scorePersistenceService.parseScoresFromResponse(rescored).get("comment");
                                String recheckComment = (rc instanceof String s) ? s : "";
                                String stripped = stripTopicMarker(recheckComment);
                                String base = (stripped == null || stripped.isEmpty()) ? comment : stripped;
                                comment = base.contains(WRONG_ANSWER_NOTE) ? base : base + WRONG_ANSWER_NOTE;
                                log.info("错误漏判复核命中，改判[错误]档按半分计 - defenseId: {}, 复核后总分: {}",
                                        defenseId, scores.get("totalScore"));
                            } else {
                                log.info("错误漏判复核维持原分 - defenseId: {}, 原总分: {}, 原因: {}",
                                        defenseId, scores.get("totalScore"),
                                        rescored == null ? "复核调用失败" : "复核未判[错误]");
                            }
                        }
                    }

                    // 【N46/N49 · 2026-10-02】点评接地校验：点评必须引用本轮答案里的具体词。
                    // 模板话（"概念阐述准确、逻辑清晰…"）与幻觉点评（评的是本场其他轮次的内容）
                    // 与本轮答案的二元组交集≈0；真实点评引用 1~2 个具体词即达标。不合格追加一次重写。
                    comment = ensureGroundedComment(comment, scores, userInput, topicId,
                            assistantCountInHistory, existingQuestionIds, trimmedHistory, defenseId);

                    // 统一口径（2026-09-26）：点评/评分两行都用服务端最终文案与分值重写，
                    // 让「前端气泡 == 落库分 == Redis 记忆」完全一致，杜绝模型自报总分与五维和打架。
                    aiResponse = normalizeCommentAndScore(aiResponse, comment, scores);

                    // 组装评分行。【N55 · 2026-10-02】此处只构建、不再直接落库——落库统一走下方
                    // 「评分行 + 答案行同事务」，消除 2026-09-29 ROLLBACK_TEST 实锤的
                    // 「评分行（异步独立事务）先提交、答案行失败 → 只落一半且前端无感」。
                    // 原 N21 的「末轮同步 / 其余异步」分流随之取消：insert 为毫秒级，
                    // 统一同步同事务落库不构成延迟，末轮聚合总分的时效不受影响。
                    DefenseScoreRecord scoreRecord = null;
                    if (defenseId != null && !scores.isEmpty()) {
                        DefenseScoreRecord record = new DefenseScoreRecord();
                        record.setDefenseId(defenseId);
                        int questionIndex = assistantCountInHistory - 1;
                        if (questionIndex >= 0 && questionIndex < existingQuestionIds.size()) {
                            record.setQuestionId(existingQuestionIds.get(questionIndex));
                        }
                        record.setRoundNum(currentRoundNum);
                        record.setExpressionScore(toBigDecimal(scores.get("expression")));
                        record.setLogicScore(toBigDecimal(scores.get("logic")));
                        record.setProfessionalScore(toBigDecimal(scores.get("professional")));
                        record.setAdaptabilityScore(toBigDecimal(scores.get("adaptability")));
                        record.setInnovationScore(toBigDecimal(scores.get("innovation")));
                        record.setComment(comment);
                        record.setCreatedAt(LocalDateTime.now());
                        scoreRecord = record;
                    }

                    Double legacyScore = null;
                    if (scores.containsKey("totalScore")) {
                        legacyScore = (Double) scores.get("totalScore");
                    }
                    if (legacyScore == null) {
                        legacyScore = extractScoreFromResponse(aiResponse);
                    }
                    String feedback = comment != null ? comment : extractFeedbackFromResponse(aiResponse);
                    // lambda 捕获要求（N55）：以下变量在闭包中使用，须为事实最终值
                    final String answerTextFinal = userInput;
                    final String feedbackFinal = feedback;
                    final Double legacyScoreFinal = legacyScore;
                    final Integer topicIdFinal = topicId;
                    final String userIdFinal = userId;
                    final DefenseScoreRecord recordFinal = scoreRecord;

                    if (assistantCountInHistory <= existingQuestionCount) {
                        try {
                            int qi = assistantCountInHistory - 1;
                            if (qi >= 0 && qi < existingQuestionIds.size()) {
                                Integer currentQuestionId = existingQuestionIds.get(qi);
                                if (recordFinal != null) {
                                    // 【N55】评分行 + 答案行同一事务：答案写入失败 → 两行一起回滚
                                    scorePersistenceService.saveScoreThenAnswer(recordFinal, () ->
                                            defenseRecordsService.savePresetQuestionAnswer(
                                                    topicIdFinal, userIdFinal, currentQuestionId,
                                                    answerTextFinal, feedbackFinal, legacyScoreFinal));
                                } else {
                                    defenseRecordsService.savePresetQuestionAnswer(
                                            topicId, userId, currentQuestionId, userInput, feedback, legacyScore);
                                }
                                log.info("预设问题回答保存成功 - questionId: {}, round: {}", currentQuestionId, currentRoundNum);
                            } else if (recordFinal != null) {
                                // 题号越界（异常兜底）：仅落评分行，保持旧行为
                                scorePersistenceService.saveRoundScore(recordFinal);
                            }
                        } catch (Exception e) {
                            log.error("保存预设问题回答时发生异常（评分行与答案行已一并回滚，本轮不计分）", e);
                        }
                    } else {
                        // 追问阶段【B1 · 2026-10-05】：defense_student_questions + defense_answers +
                        // defense_score_record 三张表纳入同一事务（saveScoreThenAnswer）——
                        // 旧实现 saveExtraQuestionPhase 与 saveRoundScore 两个独立事务，任一失败都会留半截数据；
                        // 现在评分行/答案行/追问题登记任一写入失败，整体回滚。
                        final String aiResponseFinal = aiResponse;
                        final String userInputFinal = userInput;
                        final String feedbackFinal2 = feedback;
                        final Double legacyScoreFinal2 = legacyScore;
                        final List<Message> trimmedHistoryFinal = trimmedHistory;
                        if (scoreRecord != null) {
                            try {
                                scorePersistenceService.saveScoreThenAnswer(scoreRecord, () ->
                                        saveExtraQuestionPhase(aiResponseFinal, defenseId, userId, topicId,
                                                trimmedHistoryFinal, userInputFinal, feedbackFinal2, legacyScoreFinal2));
                            } catch (Exception e) {
                                log.error("追问阶段落库失败，评分/答案/追问题已一并回滚（本轮不计分） - defenseId: {}",
                                        defenseId, e);
                            }
                        } else {
                            saveExtraQuestionPhase(aiResponse, defenseId, userId, topicId, trimmedHistory, userInput, feedback, legacyScore);
                        }
                    }

                    if (defenseId != null
                            && assistantCountInHistory < existingQuestionCount + EXTRA_QUESTION_LIMIT) {
                        String nextQuestion = extractNextQuestionFromResponse(aiResponse);
                        if ((nextQuestion == null || nextQuestion.isEmpty()) && scores.containsKey("question")) {
                            nextQuestion = (String) scores.get("question");
                        }
                        // 清洗：防止模型把"答案要点"混入下一题
                        nextQuestion = cleanNextQuestion(nextQuestion);
                        // 追问阶段去重（2026.9.15 实测"并行处理"类追问连问两遍）：
                        // 模型自拟下一题若与已问题目重复/高度相似，则重新生成一道
                        if (nextQuestion != null && !nextQuestion.isEmpty()
                                && assistantCountInHistory >= existingQuestionCount) {
                            List<String> askedQuestions = collectAskedQuestions(topicId, assistantCountInHistory - 1, defenseId);
                            if (isSimilarToAnyQuestion(nextQuestion, askedQuestions)) {
                                log.warn("模型下一题与已问题目重复/高度相似，重新生成: {}", nextQuestion);
                                String regenerated = generateFollowUpQuestion(topicId, askedQuestions,
                                        extractCurrentQuestionFromPrompt(fullPrompt), userInput);
                                if (regenerated != null && !regenerated.isEmpty()) {
                                    nextQuestion = regenerated;
                                }
                            }
                        }
                        // 【N52 · 2026-09-28】预设题阶段一律改用题库题目。原实现是"解析不到才兜底"，不够：
                        // 实测 298/299 连续两场，3B 模型没照【轮次铁律】提问库里的题，自己编了题（题库第 2/3 题
                        // 是 MapReduce 统计/QI 等级判断，模型却问出两道 Hive 题）。而后端有三处按下标假定
                        // 「AI 问出的第 N 题 == 题库第 N 题」——prompt 里的【当前题】（模型判分依据）、落库的
                        // question_id、答非所问复核的取题——一旦偏离即连锁错位：记录页显示的不是学生答的题，
                        // 复核还会拿题库错题去算覆盖率而误触发（299 第 3 轮即此例）。
                        // 改为一律覆盖：nextQuestion 往下会经 rewriteNextQuestionInResponse 写回回复串，
                        // 而前端正是从回复串解析「下一题:」渲染的，所以覆盖这里就等于决定学生看到哪道题。
                        // 追问阶段（>= existingQuestionCount）由模型自拟，是设计意图，不受影响。
                        if (assistantCountInHistory < existingQuestionCount) {
                            String presetQuestion = fetchPresetQuestionText(topicId, assistantCountInHistory);
                            if (presetQuestion != null && !presetQuestion.isEmpty()) {
                                if (!presetQuestion.equals(nextQuestion)) {
                                    log.warn("预设题阶段模型自拟题目，已强制改用题库题 - 轮次: {}, 模型原题: {}, 题库题: {}",
                                            assistantCountInHistory, nextQuestion, presetQuestion);
                                }
                                nextQuestion = presetQuestion;
                            } else {
                                log.warn("题库题获取失败，沿用模型自拟题目 - 轮次: {}", assistantCountInHistory);
                            }
                        }
                        // 回写规范后的题目文本到回复（2026.9.15）：前端展示的是 aiResponse 里的"下一题:"行，
                        // 清洗/去重/兜底替换后的题目要同步写回，避免"展示脏字符、落库干净题"两张皮
                        aiResponse = rewriteNextQuestionInResponse(aiResponse, nextQuestion);
                        if (nextQuestion != null && !nextQuestion.isEmpty()
                                && assistantCountInHistory >= existingQuestionCount) {
                            // 仅追问阶段由模型自拟的下一题才登记入库；预设题阶段不动，避免污染追问额度计数
                            submitIfNewQuestion(defenseId, nextQuestion);
                        }
                    }

                    // 最后一轮总结生成后收尾：聚合五维平均分写入 defense_records，供前端答辩记录展示
                    // 权威口径（2026-09-15 修复）：assistantCountInHistory = 本次作答序号（评分落库行数+1，免疫记忆裁剪）。
                    // 共 existingQuestionCount + EXTRA_QUESTION_LIMIT = 10 轮，第 10 次作答即收尾：
                    // ① 模型输出"总结:" → 正常收尾；② 模型仍输出"下一题:" → 剥离残留提问并补占位总结强制收尾（防死循环兜底）。
                    // 旧实现的两个收尾条件都按"历史消息中的 assistant 数"计数，被裁剪封顶在 6，两个分支均永远无法触发。
                    boolean hasSummary = aiResponse != null
                            && (aiResponse.contains(AiProtocolConstants.SUMMARY_TAG)
                            || aiResponse.contains(AiProtocolConstants.SUMMARY_TAG_FULL));
                    int totalRounds = existingQuestionCount + EXTRA_QUESTION_LIMIT;
                    boolean lastRound = assistantCountInHistory >= totalRounds;
                    if (lastRound) {
                        if (!hasSummary) {
                            // 到最后一轮模型仍未输出总结：剥离残留"下一题"并补占位总结，保证前端收到"总结:"信号正常结束
                            aiResponse = stripNextQuestionFromText(aiResponse);
                            aiResponse = (aiResponse == null ? "" : aiResponse)
                                    + "\n总结:本轮答辩已进行" + assistantCountInHistory + "轮，已自动结束。";
                            log.warn("硬上限兜底收尾：第{}轮未见总结，已强制收尾", assistantCountInHistory);
                        }
                        if (defenseId != null) {
                            // 总结带总评（2026.9.15）：总结末尾追加总分与五维汇总，学生收尾即可见成绩
                            // （总结必为回复最后一行，直接拼接即可；"总分"判重防止模型自己已输出时重复）
                            String finalScoreText = buildFinalScoreText(defenseId);
                            if (!finalScoreText.isEmpty() && !aiResponse.contains("总分")) {
                                aiResponse = aiResponse + finalScoreText;
                            }
                            finishDefenseAggregation(defenseId, extractSummaryText(aiResponse));
                        }
                    } else {
                        // 未到最后一轮：剥离提前出现的总结，防止前端误判答辩提前结束
                        String stripped = stripSummaryFromText(aiResponse);
                        if (!stripped.equals(aiResponse)) {
                            aiResponse = stripped;
                            log.warn("硬校验拦截提前总结：当前第{}轮/共{}轮，已剥离总结行",
                                    assistantCountInHistory, totalRounds);
                        }
                        // 兜底：剥离后若无"下一题/追问"，按阶段补一行干净标签，避免前端"有分无题"
                        aiResponse = appendNextQuestionFallback(aiResponse, assistantCountInHistory >= existingQuestionCount,
                                topicId, assistantCountInHistory);
                    }
                }
            }

            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                if (userInput != null && !userInput.trim().isEmpty()) {
                    chatMemory.add(sessionId, List.of(
                            new UserMessage(userInput),
                            new AssistantMessage(aiResponse)
                    ));
                } else {
                    chatMemory.add(sessionId, List.of(
                            new AssistantMessage(aiResponse)
                    ));
                }
                trimChatMemory(sessionId);
            }

        } catch (Exception e) {
            log.error("存储会话记忆失败 - sessionId: {}, error: {}", sessionId, e.getMessage(), e);
        }

        return aiResponse;
    }

    /** 用于 chat() 方法调用时包装为 Flux */
    private Flux<String> toFlux(String text) {
        return text != null ? Flux.just(text) : Flux.empty();
    }

    /**
     * 从AI回复中提取简短摘要（用于历史拼接时减少token）
     */
    private String extractBriefFromAiMessage(String aiText) {
        if (aiText == null || aiText.isEmpty()) return "";
        // 新三段式：提取 点评 内容作为摘要（不携带评分行，避免污染后续 prompt）
        // 【F1】标签统一走 AiProtocolConstants，偏移用 tag.length() 而非魔法数字
        if (aiText.contains(AiProtocolConstants.COMMENT_TAG)) {
            int start = aiText.indexOf(AiProtocolConstants.COMMENT_TAG)
                    + AiProtocolConstants.COMMENT_TAG.length();
            int end = aiText.indexOf("\n", start);
            if (end == -1) end = aiText.length();
            String c = aiText.substring(start, end).trim();
            if (!c.isEmpty()) return c.length() > 60 ? c.substring(0, 60) + "..." : c;
        }
        // 提取【评语】或【问题】作为摘要
        int commentStart = aiText.indexOf("【评语】");
        int questionStart = aiText.indexOf("【问题】");
        if (questionStart != -1) {
            String q = aiText.substring(questionStart + 4).trim();
            return q.length() > 60 ? q.substring(0, 60) + "..." : q;
        }
        if (commentStart != -1) {
            int end = aiText.indexOf("【", commentStart + 4);
            String c = end != -1 ? aiText.substring(commentStart + 4, end).trim() : aiText.substring(commentStart + 4).trim();
            return c.length() > 80 ? c.substring(0, 80) + "..." : c;
        }
        return aiText.length() > 80 ? aiText.substring(0, 80) + "..." : aiText;
    }

    /**
     * 裁剪ChatMemory中的历史消息，确保不超过 MAX_HISTORY_MESSAGES 条
     */
    private void trimChatMemory(String sessionId) {
        try {
            List<Message> all = chatMemory.get(sessionId);
            if (all.size() > MAX_HISTORY_MESSAGES) {
                chatMemory.clear(sessionId);
                List<Message> keep = all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size());
                chatMemory.add(sessionId, keep);
                log.info("Redis ChatMemory 裁剪完成 - 从 {} 条 -> {} 条", all.size(), keep.size());
            }
        } catch (Exception e) {
            log.warn("裁剪 ChatMemory 失败: {}", e.getMessage());
        }
    }

    // ==================== 额外问题保存逻辑 ====================

    private void saveExtraQuestionPhase(String aiResponse, Integer defenseId, String userId, Integer topicId,
                                        List<Message> history, String userInput, String feedback, Double score) {
        log.info("额外问题阶段");

        AiAnalysis aiAnalysis = new AiAnalysis();
        aiAnalysis.setDefenseId(defenseId);
        aiAnalysis.setUserId(userId);
        aiAnalysis.setFeedback(summary(aiResponse));
        aiAnalysis.setVideoAnalysis(videoAnalysis(aiResponse));
        aiAnalysis.setReportAnalysis(reportAnalysis(aiResponse));

        Double totalScore = extractTotalScoreFromResponse(aiResponse);
        if (totalScore != null) {
            aiAnalysis.setScore(totalScore);
        }

        if (aiAnalysis.getFeedback() != null || aiAnalysis.getVideoAnalysis() != null || aiAnalysis.getReportAnalysis() != null) {
            defenseAnswersMapper.insertAiFeedback(aiAnalysis);
        }

        if (defenseId != null) {
            String currentQuestion = extractQuestionFromLastAiMessage(history);
            if (currentQuestion != null && !currentQuestion.isEmpty()) {
                DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestion);
                Integer sqId;
                if (existingQuestion != null) {
                    sqId = existingQuestion.getSqId();
                } else {
                    int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                    DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                    studentQuestion.setDefenseId(defenseId);
                    studentQuestion.setQuestionId(null);
                    studentQuestion.setCustomQuestion(currentQuestion);
                    studentQuestion.setCustomStandardAnswer("");
                    studentQuestion.setQuestionType("ai");
                    studentQuestion.setSort(nextSort);
                    studentQuestion.setCreatedAt(LocalDateTime.now());
                    defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                    sqId = studentQuestion.getSqId();
                }

                if (sqId != null) {
                    DefenseAnswers answer = new DefenseAnswers();
                    answer.setDefenseId(defenseId);
                    answer.setQuestionId(null);
                    answer.setSqId(sqId);
                    answer.setStudentAnswer(userInput);
                    answer.setFeedback(feedback);
                    answer.setScore(score != null ? BigDecimal.valueOf(score) : null);
                    answer.setCreatedAt(LocalDateTime.now());
                    defenseAnswersMapper.insertAnswer(answer);
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 截断文本至指定长度，超出部分用"..."代替
     */
    private String truncateSummary(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Double) return BigDecimal.valueOf((Double) value);
        if (value instanceof Integer) return BigDecimal.valueOf((Integer) value);
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private int countExtraQuestions(Integer topicId, String userId) {
        try {
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) return 0;
            List<DefenseStudentQuestions> list = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            int count = 0;
            if (list != null) {
                for (DefenseStudentQuestions q : list) {
                    if ("ai".equals(q.getQuestionType())) count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("统计额外问题数失败", e);
            return 0;
        }
    }

    private int countAssistantMessages(List<Message> history) {
        int count = 0;
        for (Message msg : history) {
            if (msg instanceof AssistantMessage) count++;
        }
        return count;
    }

    // ==================== AI回复解析方法（保留原有兼容逻辑） ====================

    private Double extractScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        try {
            if (aiResponse.contains("【得分】")) {
                int start = aiResponse.indexOf("【得分】") + 4;
                int end = aiResponse.indexOf("\n", start);
                if (end == -1) end = aiResponse.length();
                String s = aiResponse.substring(start, end).trim().replaceAll("[^0-9.]", "");
                if (!s.isEmpty()) return Double.parseDouble(s);
            }
            // 新三段式：评分:38/50|...
            java.util.regex.Matcher mNew = java.util.regex.Pattern
                    .compile("评分[:：]\\s*(\\d+(?:\\.\\d+)?)/50").matcher(aiResponse);
            if (mNew.find()) return Double.parseDouble(mNew.group(1));
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分");
            java.util.regex.Matcher m = p.matcher(aiResponse);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            log.warn("提取分数失败: {}", e.getMessage());
        }
        return null;
    }

    private Double extractTotalScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        try {
            if (aiResponse.contains("【总得分】")) {
                int start = aiResponse.indexOf("【总得分】") + 5;
                int end = aiResponse.indexOf("\n", start);
                if (end == -1) end = aiResponse.length();
                String s = aiResponse.substring(start, end).trim().replaceAll("[^0-9.]", "");
                if (!s.isEmpty()) return Double.parseDouble(s);
            }
        } catch (Exception e) {
            log.warn("提取总得分失败: {}", e.getMessage());
        }
        return null;
    }

    private String extractFeedbackFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        try {
            // 新三段式：点评:...
            // 【F1】标签常量化 + tag.length() 偏移
            if (aiResponse.contains(AiProtocolConstants.COMMENT_TAG)) {
                int start = aiResponse.indexOf(AiProtocolConstants.COMMENT_TAG)
                        + AiProtocolConstants.COMMENT_TAG.length();
                int end = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG, start);
                if (end == -1) end = aiResponse.length();
                String d = aiResponse.substring(start, end).trim();
                if (!d.isEmpty()) return d;
            }
            if (aiResponse.contains("【评价】")) {
                int start = aiResponse.indexOf("【评价】") + 4;
                int end = aiResponse.length();
                if (aiResponse.contains("【得分】")) end = aiResponse.indexOf("【得分】");
                else if (aiResponse.contains("【问题】")) end = aiResponse.indexOf("【问题】");
                else if (aiResponse.contains("【总结】")) end = aiResponse.indexOf("【总结】");
                return aiResponse.substring(start, end).trim();
            }
        } catch (Exception e) {
            log.warn("提取评价失败: {}", e.getMessage());
        }
        return aiResponse;
    }

    private String extractNextQuestionFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        // 新三段式：下一题:...【F1】标签常量化
        if (aiResponse.contains(AiProtocolConstants.NEXT_QUESTION_TAG)) {
            int start = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG)
                    + AiProtocolConstants.NEXT_QUESTION_TAG.length();
            String q = aiResponse.substring(start).trim();
            if (!q.isEmpty()) return q;
        }
        if (aiResponse.contains("【问题】")) {
            int start = aiResponse.indexOf("【问题】") + 4;
            String q = aiResponse.substring(start).trim();
            if (!q.isEmpty()) return q;
        }
        return null;
    }

    /** 清洗下一题文本：只保留第一行并截断"答案要点"等内容，防止评分参考或总结串入题目 */
    private String cleanNextQuestion(String q) {
        if (q == null) return null;
        String cleaned = q.trim();
        int nl = cleaned.indexOf('\n');
        if (nl > -1) cleaned = cleaned.substring(0, nl).trim();
        int idx = cleaned.indexOf("要点");
        if (idx > 0) cleaned = cleaned.substring(0, idx).trim();
        // 2026-09-15 题目文本清洗（实测模型输出"？如何…？？"类脏字符）：
        // 开头杂标点剥掉；结尾连续问号收敛为一个"？"（保留疑问句式）；结尾其他杂标点去掉
        cleaned = cleaned.replaceAll("^[？?。，,、：:；;．.\\s～~·]+", "");
        cleaned = cleaned.replaceAll("[？?][\\s]*[？?]+[\\s]*$", "？");
        cleaned = cleaned.replaceAll("[。，,、：:；;．.\\s～~·]+$", "");
        if (cleaned.isEmpty() || "无".equals(cleaned)) return null;
        return cleaned;
    }

    /**
     * 将规范后的题目文本回写到回复的"下一题:"行（含全角冒号，2026.9.15 配套题目清洗/追问去重）：
     * 前端展示与记忆里的都是 aiResponse 原文，清洗/去重/兜底替换后的题目必须写回，否则"展示脏题、落库净题"两张皮。
     * 回复中没有"下一题"标记时不动作（该场景由 appendNextQuestionFallback 补行）。
     */
    private String rewriteNextQuestionInResponse(String aiResponse, String question) {
        if (aiResponse == null || question == null || question.isEmpty()) return aiResponse;
        // 【F1】标签常量化 + tag.length() 偏移
        int pos = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG);
        int posFull = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG_FULL);
        if (pos == -1 || (posFull != -1 && posFull < pos)) {
            pos = posFull;
        }
        if (pos == -1) return aiResponse;
        int tagLen = (pos == posFull) ? AiProtocolConstants.NEXT_QUESTION_TAG_FULL.length()
                : AiProtocolConstants.NEXT_QUESTION_TAG.length();
        return aiResponse.substring(0, pos + tagLen) + question;
    }

    /**
     * 还未到最后一轮时，剥离模型提前输出的"总结"行（含其后内容）。
     * 仅在题库模式（existingQuestionCount > 0）下生效；追问额度用完的最后一轮允许总结。
     */
    private String stripPrematureSummary(String aiResponse, int existingQuestionCount, int extraAskedCount) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        if (existingQuestionCount <= 0) return aiResponse;
        // 权威口径（2026-09-15 修复）：extraAskedCount = 本轮之前已完成的追问次数。最后一次追问
        // （第 EXTRA_QUESTION_LIMIT 次）作答时该值恰为 EXTRA_QUESTION_LIMIT - 1，此时模型的"总结:"
        // 是合法收尾信号，不能剥离（旧阈值会把它剥掉，导致最后一轮只能走占位总结兜底）
        if (extraAskedCount >= EXTRA_QUESTION_LIMIT - 1) return aiResponse;

        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1) return aiResponse;

        if (pos == 0) {
            // 整条回复只有总结：无法安全剥离，保留原样并告警（提示词已约束，正常流程极少出现）
            log.warn("模型在非最后一轮输出了纯总结回复，未剥离: {}", aiResponse);
            return aiResponse;
        }
        log.warn("模型在非最后一轮提前输出总结，已剥离: {}", aiResponse.substring(pos));
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 纯文本剥离函数：移除回复中"总结:"（含全角）及其后内容。
     * 供硬校验兜底使用（区别于 stripPrematureSummary 的额度判断）。
     */
    private String stripSummaryFromText(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1 || pos == 0) return aiResponse;
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 纯文本剥离函数：移除回复中"下一题:"（含全角）及其后内容。
     * 供最后一轮强制收尾使用：模型到第 10 轮仍输出"下一题:"时，先剥掉残留提问再补占位总结，
     * 保证前端收到的收尾消息干净（只有点评/评分/总结）。
     */
    private String stripNextQuestionFromText(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        // 【F1】标签常量化
        int pos = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG);
        int posFull = aiResponse.indexOf(AiProtocolConstants.NEXT_QUESTION_TAG_FULL);
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1 || pos == 0) return aiResponse;
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 硬校验兜底的补充行：剥离总结后，若 aiResponse 未携带"下一题/追问"标签，
     * 按阶段补一行，保证前端始终能拿到下一题/追问：
     *  - 预设未答完：从题库取下一题
     *  - 追问阶段：调 generateFollowUpQuestion 生成
     *  统一拼"下一题:"标签（前端 parseSegmentFormat 只认 下一题/下一问，不认"追问:"）。
     */
    private String appendNextQuestionFallback(String aiResponse, boolean presetDone, Integer topicId,
                                              int assistantCountInHistory) {
        if (aiResponse == null) return null;
        // 已含下一题或追问标签则不用补【F1】标签常量化
        if (aiResponse.contains(AiProtocolConstants.NEXT_QUESTION_TAG)
                || aiResponse.contains(AiProtocolConstants.NEXT_QUESTION_TAG_FULL)
                || aiResponse.contains("追问:") || aiResponse.contains("追问：")) {
            return aiResponse;
        }
        String nextQuestion;
        if (!presetDone) {
            // 预设阶段：取题库中当前轮次的下一道题（assistantCountInHistory 同样用于该下标）
            nextQuestion = fetchPresetQuestionText(topicId, assistantCountInHistory);
        } else {
            nextQuestion = generateFollowUpQuestion(topicId,
                    collectAskedQuestions(topicId, assistantCountInHistory - 1, null));
        }
        if (nextQuestion == null || nextQuestion.trim().isEmpty()) {
            return aiResponse;
        }
        // 统一用"下一题:"标签：前端 parseSegmentFormat 只认 下一题/下一问，不认"追问:"
        return aiResponse + "\n下一题:" + nextQuestion.trim();
    }

    // ==================== 放弃作答（"不知道/不会"）固定流程 ====================

    /** 判定为放弃作答的短语 */
    private static final String[] GIVE_UP_PHRASES = {
            "不知道", "不会", "不清楚", "不了解", "不懂", "没学过", "没复习", "没准备", "没印象", "跳过",
            // 2026-09-15 补充：contains 匹配要求连续子串，"不太清楚/不太会"等带语气词的变体原本漏判
            // （实测同一"不太清楚"三次得分 25/0/20 口径不一），故显式补充常见变体
            "不太清楚", "太不清楚", "不太懂", "不太会"
    };

    /** 放弃作答判定的最大回答长度（过长回答不视为放弃，走正常评分） */
    private static final int GIVE_UP_MAX_LENGTH = 15;

    private boolean isGiveUpAnswer(String userInput) {
        if (userInput == null) return false;
        String text = userInput.trim();
        if (text.isEmpty() || text.length() > GIVE_UP_MAX_LENGTH) return false;
        for (String phrase : GIVE_UP_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /** 判定为敷衍/无实质作答的短语（归一化+首尾修饰剥离后精确相等才命中，避免子串误伤正常回答，如"一般用快排"） */
    private static final String[] JUNK_ANSWER_PHRASES = {
            "开始", "一般", "一般般", "差不多", "还好", "还行", "还可以", "就这样", "就这", "随便", "过",
            "好的", "知道了", "明白了", "没什么", "没啥说的",
            "不太行", "不行", "你是对的", "我是对的", "你说的对", "你说得对", "有道理", "对的", "是的", "没错",
            "我会这道题", "这题我会", "我会", "我会做", "我知道", "我知道这个", "我知道答案",
            "简单", "很简单", "挺简单", "太简单", "很容易", "没问题",
            "说不上来", "答不上来", "忘了", "忘记了",
            // 2026-09-15 实测补网：报数式玩笑（"我是250"曾漏判得8分）、骂人单字（精确相等匹配，不会误伤正常回答）
            "我是250", "250", "滚",
            "ok", "okay", "next", "emm", "emmm"
    };

    /** 语气词/填充字符：回答仅由这些字符组成（"额""嗯嗯""额啊"等任意组合）视为敷衍输入 */
    private static final String FILLER_CHARS = "嗯哦啊呃额唔哎嘿诶唉噢喔哈呀哇";

    /**
     * 敷衍/无实质作答判定（2026-09-15 新增，同日实测后二次加强）：与放弃作答同走固定零分流程，不调用评分模型。
     * 归一化：转小写、去空白/标点/符号，再剥离开头缓和语（感觉/我觉得/我认为）与结尾语气词（吧/呢/啊…），
     * 覆盖"一般吧""还好吧""感觉不太行"等实测漏网变体。判定：①剥离后为空（纯标点/纯语气词，如"？？""额"）；
     * ②1~2位纯数字（如"1"）；③仅由语气词字符组成；④与敷衍短语精确相等（含"我会这道题"等只声称会但不回答）。
     * 3位及以上纯数字（如端口号"3306"）可能是有效简答，不在此判定，交由评分模型按提示词规则给分。
     */
    private boolean isJunkAnswer(String userInput) {
        if (userInput == null) return false;
        String trimmed = userInput.trim();
        if (trimmed.isEmpty()) return false;
        String text = trimmed.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
        text = text.replaceAll("^(感觉|我觉得|我认为)+", "");
        text = text.replaceAll("[吧呢啊呀哦嘛呗啦哟唷哇哈么]+$", "");
        if (text.isEmpty()) return true;
        if (text.matches("[0-9]{1,2}")) return true;
        boolean allFiller = true;
        for (int i = 0; i < text.length(); i++) {
            if (FILLER_CHARS.indexOf(text.charAt(i)) < 0) { allFiller = false; break; }
        }
        if (allFiller) return true;
        // 复读型敷衍（15:44 实测漏网补网）："对对对""嗯嗯""666"（同一字符重复）与"对的对的""是的是的"（双字单元重复）
        if (text.length() >= 2 && text.chars().distinct().count() == 1) return true;
        if (text.length() >= 4 && text.length() % 2 == 0
                && text.substring(0, text.length() / 2).equals(text.substring(text.length() / 2))) return true;
        for (String phrase : JUNK_ANSWER_PHRASES) {
            if (text.equals(phrase)) return true;
        }
        return false;
    }

    /**
     * 学生放弃作答的固定处理：本题五维0分、不调用评分模型，直接给出下一题或收尾总结。
     *
     * @param zeroComment 固定零分点评文案（按判定来源区分：放弃作答/敷衍作答）
     * @return 固定模板响应；返回 null 表示轮次/题库异常，需回退正常模型流程
     */
    private String handleGiveUpAnswer(List<Integer> existingQuestionIds, int existingQuestionCount,
                                      String userId, Integer topicId,
                                      String userInput, String sessionId, List<Message> history, int answeredCount,
                                      String zeroComment) {
        try {
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) {
                log.warn("放弃作答处理：无法获取答辩记录，回退正常流程 - topicId: {}, userId: {}", topicId, userId);
                return null;
            }

            // 权威轮次（2026-09-15 修复）：本次作答序号 = 已落库行数 + 1。旧实现按"历史中的 assistant 消息数"
            // 计数，被记忆裁剪封顶在 6（实测放弃轮次号全部卡在 6）。
            int currentRound = answeredCount + 1;
            int qi = currentRound - 1;
            boolean inPresetPhase = qi >= 0 && qi < existingQuestionCount;
            // 本轮已是最后一轮（第 existingQuestionCount + EXTRA_QUESTION_LIMIT 次作答）→ 本题0分后直接收尾总结
            boolean lastRound = currentRound >= existingQuestionCount + EXTRA_QUESTION_LIMIT;

            // 先确定下一题：预设题未问完 → 题库取下一题；未到最后一轮 → 模型生成追问；否则收尾。
            // 旧实现的 quotaRemains 基于 countExtraQuestions（DB 追问题条数虚高≈2倍），会导致提前收尾。
            boolean terminal = false;
            String nextQuestion = null;
            if (inPresetPhase && qi + 1 < existingQuestionCount) {
                nextQuestion = fetchPresetQuestionText(topicId, qi + 1);
            } else if (!lastRound) {
                nextQuestion = generateFollowUpQuestion(topicId,
                        collectAskedQuestions(topicId, currentRound - 1, defenseId));
            } else {
                terminal = true;
            }
            if (!terminal && (nextQuestion == null || nextQuestion.isEmpty())) {
                log.warn("放弃作答处理：下一题获取失败，回退正常流程 - topicId: {}", topicId);
                return null;
            }

            // 落库：评分记录 + 回答记录【B1】放弃轮次同样走「评分行 + 答案行」同事务，
            // 不再是两笔独立写入（旧实现评分行异步落库、答案行同步落库，失败会留半截）
            if (inPresetPhase) {
                DefenseScoreRecord giveUpRecord = buildGiveUpScoreRecord(
                        defenseId, existingQuestionIds.get(qi), currentRound, zeroComment);
                try {
                    scorePersistenceService.saveScoreThenAnswer(giveUpRecord, () ->
                            defenseRecordsService.savePresetQuestionAnswer(
                                    topicId, userId, existingQuestionIds.get(qi), userInput, zeroComment, 0.0));
                } catch (Exception e) {
                    log.error("放弃轮次落库失败，评分/答案已一并回滚 - defenseId: {}", defenseId, e);
                }
            } else {
                DefenseScoreRecord giveUpRecord = buildGiveUpScoreRecord(defenseId, null, currentRound, zeroComment);
                try {
                    scorePersistenceService.saveScoreThenAnswer(giveUpRecord, () ->
                            saveGiveUpFollowUpAnswer(defenseId, history, userInput, zeroComment, 0.0));
                } catch (Exception e) {
                    log.error("放弃轮次落库失败，评分/答案已一并回滚 - defenseId: {}", defenseId, e);
                }
            }

            // 仅当本轮抛出的是"新追问题"时才登记入库（预设最后一题切换到追问的第一题也在此登记）
            boolean nextIsNewFollowUp = !terminal && !(inPresetPhase && qi + 1 < existingQuestionCount);
            if (nextIsNewFollowUp) {
                submitIfNewQuestion(defenseId, nextQuestion);
            }

            String fixedResponse;
            if (terminal) {
                // 总结带总评（2026.9.15）：追加分总分与五维汇总
                String summary = "本次答辩到此结束，系统已按各轮评分汇总最终成绩，可在答辩记录中查看。"
                        + buildFinalScoreText(defenseId);
                fixedResponse = "点评:" + zeroComment + "\n评分:0/50|0|0|0|0|0\n总结:" + summary;
                finishDefenseAggregation(defenseId, summary);
            } else {
                fixedResponse = "点评:" + zeroComment + "\n评分:0/50|0|0|0|0|0\n下一题:" + nextQuestion;
            }

            chatMemory.add(sessionId, List.of(
                    new UserMessage(userInput),
                    new AssistantMessage(fixedResponse)
            ));
            trimChatMemory(sessionId);

            log.info("学生放弃/敷衍作答，走固定零分流程 - defenseId: {}, round: {}, terminal: {}, 点评: {}",
                    defenseId, currentRound, terminal, zeroComment);
            return fixedResponse;
        } catch (Exception e) {
            log.error("放弃作答固定流程异常，回退正常模型流程", e);
            return null;
        }
    }

    /** 从题库获取指定下标的预设题文本 */
    private String fetchPresetQuestionText(Integer topicId, int index) {
        try {
            Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
            if (qr.getCode() == 1 && qr.getData() != null && index >= 0 && index < qr.getData().size()) {
                return qr.getData().get(index).getQuestion();
            }
        } catch (Exception e) {
            log.warn("题库获取下一题失败: {}", e.getMessage());
        }
        return null;
    }

    /** 追问阶段：让模型围绕课题与学生薄弱点出一个新的追问问题（仅输出问题本身） */
    private String generateFollowUpQuestion(Integer topicId) {
        return generateFollowUpQuestion(topicId, new ArrayList<>(), null, null);
    }

    private String generateFollowUpQuestion(Integer topicId, List<String> excludeQuestions) {
        return generateFollowUpQuestion(topicId, excludeQuestions, null, null);
    }

    /**
     * 追问阶段：让模型围绕课题、当前题和学生刚才回答出一个新的追问问题（仅输出问题本身）。
     * excludeQuestions 为已问过的题目；生成后同时做字面相似与主题关键词相似判断，避免 N48 的同主题换皮追问。
     */
    private String generateFollowUpQuestion(Integer topicId, List<String> excludeQuestions,
                                            String currentQuestion, String studentAnswer) {
        String topicName = "";
        String topicDescription = "";
        List<DefenseQuestions> presetQuestions = new ArrayList<>();
        try {
            Result<Object> topicResult = defenseTopicsService.getTopicById(topicId);
            if (topicResult.getCode() == 1 && topicResult.getData() instanceof TopicDto topicDto) {
                topicName = topicDto.getTopicName();
                topicDescription = topicDto.getTopicDescription();
            }
        } catch (Exception e) {
            log.warn("获取课题信息失败: {}", e.getMessage());
        }
        try {
            Result<List<DefenseQuestions>> questionResult = defenseTopicsService.getDefenseQuestionById(topicId);
            if (questionResult.getCode() == 1 && questionResult.getData() != null) {
                presetQuestions = questionResult.getData();
            }
        } catch (Exception e) {
            log.warn("获取题库摘要失败: {}", e.getMessage());
        }

        StringBuilder pb = new StringBuilder();
        pb.append("你是一名答辩考官，正在考核学生的课题《").append(topicName).append("》。\n");
        if (topicDescription != null && !topicDescription.trim().isEmpty()) {
            pb.append("课题简介:").append(truncateForPrompt(topicDescription, 120)).append("\n");
        }
        if (currentQuestion != null && !currentQuestion.trim().isEmpty()) {
            pb.append("刚才题目:").append(truncateForPrompt(currentQuestion, FOLLOW_UP_REFERENCE_LENGTH)).append("\n");
        }
        if (studentAnswer != null && !studentAnswer.trim().isEmpty()) {
            pb.append("学生刚才回答:").append(truncateForPrompt(studentAnswer, FOLLOW_UP_ANSWER_CONTEXT_LENGTH)).append("\n");
        }
        if (!presetQuestions.isEmpty()) {
            pb.append("题库范围摘要:\n");
            for (int i = 0; i < Math.min(5, presetQuestions.size()); i++) {
                DefenseQuestions q = presetQuestions.get(i);
                pb.append(i + 1).append(". ").append(truncateForPrompt(q.getQuestion(), FOLLOW_UP_REFERENCE_LENGTH));
                if (q.getStandardAnswer() != null && !q.getStandardAnswer().isBlank()) {
                    pb.append("；要点:").append(truncateForPrompt(q.getStandardAnswer(), FOLLOW_UP_REFERENCE_LENGTH));
                }
                pb.append("\n");
            }
        }
        if (excludeQuestions != null && !excludeQuestions.isEmpty()) {
            pb.append("已问过的问题/主题（新问题不得重复、不得换个说法再问）：");
            for (String q : excludeQuestions) {
                pb.append("\n- ").append(truncateForPrompt(q, FOLLOW_UP_REFERENCE_LENGTH));
            }
            pb.append("\n");
        }
        pb.append("请基于学生刚才回答中最薄弱或最含糊的一点追问，优先追问实现细节、方案取舍、边界条件、性能瓶颈或异常处理。")
                .append("禁止泛泛提问‘不足与改进方向’，禁止重复已问主题。只输出一个新问题，30字以内，以？结尾，不要输出解释。\n");

        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String resp = chatClient.prompt()
                        .user(pb.toString())
                        .options(OpenAiChatOptions.builder()
                                .model(ollamaModelName)
                                .temperature(0.0)
                                .maxTokens(60)
                                .build())
                        .call()
                        .content();
                String question = cleanNextQuestion(resp);
                if (question != null && !question.isEmpty()
                        && !isSimilarToAnyQuestion(question, excludeQuestions)) {
                    return question;
                }
                log.warn("追问生成与已问题目相似或为空（第{}次尝试），重试", attempt + 1);
                pb.append("上一候选问题因重复或为空已被拒绝，请换一个不同考点继续生成。\n");
            }
        } catch (Exception e) {
            log.warn("模型生成追问失败，使用兜底问题: {}", e.getMessage());
        }

        String[] fallbacks = buildFollowUpFallbacks(currentQuestion, studentAnswer);
        // 兜底题按固定顺序取第一个不相似项，避免随机性影响 N7 复现
        for (String f : fallbacks) {
            if (!isSimilarToAnyQuestion(f, excludeQuestions)) {
                return f;
            }
        }
        return fallbacks[0];
    }

    private String[] buildFollowUpFallbacks(String currentQuestion, String studentAnswer) {
        String focus = extractFirstKeyword(studentAnswer);
        if (focus.isEmpty()) {
            focus = extractFirstKeyword(currentQuestion);
        }
        if (focus.isEmpty()) {
            focus = "该课题核心方案";
        }
        return new String[] {
                "请说明" + focus + "的关键实现细节？",
                "如果" + focus + "出现异常，你如何排查？",
                "请分析" + focus + "的性能瓶颈？",
                "请说明" + focus + "的数据流转过程？"
        };
    }

    private String truncateForPrompt(String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) + "..." : normalized;
    }

    private String extractCurrentQuestionFromPrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?m)^当前题[:：](.+)$").matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * 收集已问过的题目（2026.9.15 追问去重用）：预设题已问部分（前 askedPresetCount 道）+ 该答辩已登记的追问题。
     * defenseId 为 null 时只收集预设题（如 appendNextQuestionFallback 路径拿不到 defenseId）。
     */
    private List<String> collectAskedQuestions(Integer topicId, int askedPresetCount, Integer defenseId) {
        List<String> asked = new ArrayList<>();
        try {
            Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
            if (qr.getCode() == 1 && qr.getData() != null) {
                for (int i = 0; i < Math.min(askedPresetCount, qr.getData().size()); i++) {
                    asked.add(qr.getData().get(i).getQuestion());
                }
            }
        } catch (Exception e) {
            log.warn("收集已问预设题失败: {}", e.getMessage());
        }
        if (defenseId != null) {
            try {
                List<DefenseStudentQuestions> customs = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
                if (customs != null) {
                    for (DefenseStudentQuestions q : customs) {
                        if (q.getCustomQuestion() != null && !q.getCustomQuestion().isEmpty()) {
                            asked.add(q.getCustomQuestion());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("收集已登记追问题失败: {}", e.getMessage());
            }
        }
        return asked;
    }

    /**
     * 题目相似判定（N48 增强）：先做字面去重，再用技术词/主题词集合做同主题去重。
     */
    private boolean isSimilarToAnyQuestion(String question, List<String> askedList) {
        if (question == null || askedList == null || askedList.isEmpty()) return false;
        String a = normalizeForCompare(question);
        if (a.isEmpty()) return false;
        Set<String> aKeywords = extractQuestionKeywords(question);
        for (String asked : askedList) {
            String b = normalizeForCompare(asked);
            if (b.isEmpty()) continue;
            if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
            if (bigramDice(a, b) > 0.5) return true;
            if (isSameTopicByKeywords(aKeywords, extractQuestionKeywords(asked))) return true;
        }
        return false;
    }

    private boolean isSameTopicByKeywords(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int overlap = 0;
        for (String keyword : a) {
            if (b.contains(keyword)) overlap++;
        }
        if (overlap >= FOLLOW_UP_TOPIC_OVERLAP_MIN) return true;
        int union = a.size() + b.size() - overlap;
        return union > 0 && (double) overlap / union >= FOLLOW_UP_TOPIC_JACCARD;
    }

    private Set<String> extractQuestionKeywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return keywords;
        String lower = text.toLowerCase();
        String[] domainTerms = {
                "hadoop", "hdfs", "mapreduce", "yarn", "hive", "hbase", "spark", "flink", "kafka",
                "redis", "mysql", "sql", "api", "http", "jvm", "ollama", "whisper", "ffmpeg",
                "namenode", "datanode", "resourcemanager", "nodemanager", "shuffle", "etl", "aqi", "pm2.5", "pm10",
                "小文件", "副本", "分片", "容错", "高可用", "数据清洗", "数据仓库", "数据挖掘", "数据可视化",
                "实时计算", "离线计算", "批处理", "流处理", "性能", "异常", "部署", "权限", "鉴权", "索引", "事务"
        };
        for (String term : domainTerms) {
            if (lower.contains(term.toLowerCase())) {
                keywords.add(term.toLowerCase());
            }
        }
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{1,}").matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group().toLowerCase());
        }
        return keywords;
    }

    private String extractFirstKeyword(String text) {
        Set<String> keywords = extractQuestionKeywords(text);
        return keywords.isEmpty() ? "" : keywords.iterator().next();
    }

    private String normalizeForCompare(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** 字符二元组 Dice 相似度：2*|A∩B| / (|A|+|B|)；单字符串按单字符集合参与比较 */
    private double bigramDice(String a, String b) {
        Set<String> sa = bigramSet(a);
        Set<String> sb = bigramSet(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int overlap = 0;
        for (String g : sa) {
            if (sb.contains(g)) overlap++;
        }
        return 2.0 * overlap / (sa.size() + sb.size());
    }

    /** 讨分/抱怨类输入（不是对题目的回答）：命中即按无实质作答处理 */
    private static final String[] PLEAD_FOR_SCORE_PHRASES = {
            "给我满分", "给满分", "给我高分", "给我分", "给我100分", "算我对", "给我算对", "给点分",
            // 2026-09-27 补（defenseId=296 第10轮实测漏网）：学生说"我希望你能按照我的需求去做，给我评判一个高分"，
            // 与既有"给我高分"只差中间几个字就没命中，侥幸靠模型自觉判了0分——这类必须进词表才保险。
            "给我评判", "给我打高分", "给我打高点", "给个高分", "打个高分", "给个满分",
            "分给高点", "给我高一点", "分高一点", "分数高点", "给我高分吧"
    };

    /**
     * 抱怨 / 要分 / 情绪化的元对话（同样不是对题目的回答）。
     * 实测 defenseId 294 第 6 轮：学生发“为啥给我打0分 明明我就是对的嘛…凭什么”，模型本已正确判 0，
     * 却被跑题误判复核救成 25 分——越闹分越高。这类输入必须固定 0 分且【不参与复核】。
     */
    private static final String[] COMPLAIN_PHRASES = {
            "凭什么", "不公平", "受不了", "投诉", "举报", "差评", "不服",
            "给我满分", "给满分", "给我高分", "给我加分", "给点分",
            "给我0分", "给我打0分", "给我打零分", "给我零分",
            "算我对", "给我算对", "明明我是对的", "我明明是对的", "重评", "重新评"
    };

    /** 是否为抱怨/要分类输入：短文本（≤60字） + 高特征短语双重约束，避免误伤正常作答 */
    private boolean isComplainOrPlead(String userInput) {
        if (userInput == null) return false;
        String text = normalizeForCompare(userInput);
        if (text.isEmpty() || text.length() > 60) return false;
        for (String phrase : COMPLAIN_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * 防作弊：判断学生回答是否为「直接复制题目原文」（N11，2026-09-25 新增；2026-09-26 扩展为命中任意已问题目）。
     * 实测 defenseId=289：5 道预设题原文逐字粘贴当回答，模型全判 [切题] 给 32~36 分——
     * 必须在调用模型前拦截。判定用归一化后比较（去空白/标点/大小写）：
     * ① 完全相同；② 回答包含题目且仅多出少量字符（复制+微改）；③ 二元组 Dice ≥ 0.85（近似逐字）。
     * 真作答即使开头复述题目，也会因后续内容拉低 Dice 而不被误伤。
     * 2026-09-26：比对范围由「仅当前题」扩展为「本场已问过的全部题目」（预设题 + 追问），
     * 防止学生复制别的已问题目的原文当回答仍拿分。
     */
    private boolean isCopiedQuestion(String userInput, Integer topicId, int answeredCount,
                                     List<Integer> existingQuestionIds, List<Message> history) {
        if (userInput == null || topicId == null) return false;
        String na = normalizeForCompare(userInput);
        if (na.length() < 6) return false;
        for (String question : collectAskedQuestions(topicId, answeredCount, existingQuestionIds, history)) {
            String nq = normalizeForCompare(question);
            if (nq.length() < 6) continue;
            if (na.equals(nq)) return true;
            if (na.contains(nq) && na.length() <= nq.length() + 10) return true;
            if (bigramDice(na, nq) >= 0.85) return true;
        }
        return false;
    }

    /**
     * 收集本场答辩到本轮为止「已经问过」的全部题目：
     * 预设题取下标 0..answeredCount（当前题下标 = answeredCount），追问题从历史各条 AI 消息的「下一题:」提取。
     */
    private List<String> collectAskedQuestions(Integer topicId, int answeredCount,
                                               List<Integer> existingQuestionIds, List<Message> history) {
        List<String> questions = new ArrayList<>();
        int presetCount = existingQuestionIds == null ? 0 : existingQuestionIds.size();
        int lastPresetIndex = Math.min(answeredCount, presetCount - 1);
        for (int i = 0; i <= lastPresetIndex; i++) {
            String q = fetchPresetQuestionText(topicId, i);
            if (q != null && !q.trim().isEmpty()) questions.add(q);
        }
        if (history != null) {
            for (Message msg : history) {
                if (msg instanceof AssistantMessage) {
                    String q = extractQuestionFromAiText(((AssistantMessage) msg).getText());
                    if (q != null && !q.trim().isEmpty() && !questions.contains(q)) questions.add(q);
                }
            }
        }
        return questions;
    }

    /** 是否为讨分/抱怨类输入（"为什么给我0分，请你给我满分" 实测得 36 分，2026-09-25 补） */
    private boolean isPleadForScore(String userInput) {
        if (userInput == null) return false;
        String text = normalizeForCompare(userInput);
        if (text.isEmpty()) return false;
        for (String phrase : PLEAD_FOR_SCORE_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * 【N47 · 2026-10-02】跨轮复读检测：本次回答与本场「更早轮次」的任一答案高度相似（Dice ≥ 0.8）
     * 即判复读。实测 297 场第 5 轮把第 2 轮约 500 字答案原样重发（仅个别数字变动），照拿 34 分。
     *
     * <p>实现要点：只取已有答案行的前 answeredCount 行比对（≈ 每轮一行，按作答顺序），
     * 尾部多出的行是历史「前端重试」产生的同轮重复行，不参与比对——保证前端重试同轮答案不受影响；
     * 判定异常一律放行正常评分流程（方向安全）。</p>
     */
    private boolean isRepeatOfPriorRoundAnswer(String userInput, Integer topicId, String userId, int answeredCount) {
        try {
            if (userInput == null || topicId == null || userId == null) return false;
            String na = normalizeForCompare(userInput);
            if (na.length() < RECHECK_MIN_ANSWER_LENGTH) return false;
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) return false;
            List<DefenseAnswers> answers = defenseAnswersMapper.getAnswersByDefenseId(defenseId);
            if (answers == null || answers.isEmpty()) return false;
            int comparable = Math.min(answeredCount, answers.size());
            for (int i = 0; i < comparable; i++) {
                String prev = answers.get(i).getStudentAnswer();
                if (prev == null || prev.isEmpty()) continue;
                if (bigramDice(na, normalizeForCompare(prev)) >= CROSS_ROUND_REPEAT_DICE) {
                    log.info("跨轮复读命中 - 与第 {} 条历史答案 Dice ≥ {}，判复读",
                            i + 1, CROSS_ROUND_REPEAT_DICE);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("跨轮复读检测异常，放行正常评分流程: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 【N53 · 2026-10-02】是否为「明显跑题」：回答与当前题的二元组覆盖率低于救回门槛
     * （≈零相关）。用于跑题救回复核前的一道闸——明显跑题的长回答不再送复核救回。
     * 取不到当前题时返回 false（不拦截，维持旧行为，方向安全）。
     */
    private boolean isClearlyOffTarget(String userInput, Integer topicId, int questionIndex,
                                       List<Integer> existingQuestionIds, List<Message> trimmedHistory) {
        try {
            String question = getQuestionTextForRound(topicId, questionIndex, existingQuestionIds, trimmedHistory);
            if (question == null || question.isEmpty()) return false;
            return questionCoverage(question, userInput) < RESCUE_MIN_COVERAGE;
        } catch (Exception e) {
            log.warn("跑题救回门槛计算异常，放行复核: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 【N46/N49 · 2026-10-02】点评接地校验：点评必须引用「本轮答案」里的具体词。
     *
     * <p>判据：点评与本轮答案（归一化后）的二元组交集 &lt; {@link #GROUNDED_MIN_HITS} →
     * 不接地（模板话 / 幻觉——2026-10-02 基线第 3 轮答 AQI、点评却是第 2 轮 MapReduce 内容的实锤）。
     * 不合格时追加一次「带本题 + 本轮回答原文」的重写调用；重写结果自身也不接地或调用失败
     * 则维持原点评（方向安全）。零分路径的点评是服务端固定文案，不参与校验。</p>
     */
    private String ensureGroundedComment(String comment, Map<String, Object> scores, String userInput,
                                         Integer topicId, int assistantCountInHistory,
                                         List<Integer> existingQuestionIds, List<Message> trimmedHistory,
                                         Integer defenseId) {
        try {
            if (comment == null || comment.isEmpty() || userInput == null) return comment;
            Object t = (scores == null) ? null : scores.get("totalScore");
            double total = (t instanceof Number n) ? n.doubleValue() : -1;
            if (total <= 0) return comment;
            String normAnswer = normalizeForCompare(userInput);
            if (normAnswer.length() < RECHECK_MIN_ANSWER_LENGTH) return comment;
            Set<String> answerBigrams = bigramSet(normAnswer);
            if (answerBigrams.size() < 8) return comment;
            Set<String> commentBigrams = bigramSet(normalizeForCompare(comment));
            int hits = 0;
            for (String bg : answerBigrams) {
                if (commentBigrams.contains(bg)) hits++;
            }
            if (hits >= GROUNDED_MIN_HITS) return comment;

            log.warn("点评疑似模板话/幻觉（与本轮答案仅 {} 处词组重叠），触发重写 - defenseId: {}, 原点评: {}",
                    hits, defenseId, comment);
            String question = getQuestionTextForRound(topicId, assistantCountInHistory - 1,
                    existingQuestionIds, trimmedHistory);
            String rewritten = rewriteGroundedComment(question, userInput, comment);
            if (rewritten != null) {
                Set<String> newBigrams = bigramSet(normalizeForCompare(rewritten));
                int rehits = 0;
                for (String bg : answerBigrams) {
                    if (newBigrams.contains(bg)) rehits++;
                }
                if (rehits >= 2) {
                    log.info("点评重写成功 - defenseId: {}, 新点评: {}", defenseId, rewritten);
                    return rewritten;
                }
                log.info("点评重写结果仍不接地（{} 处重叠），维持原点评 - defenseId: {}", rehits, defenseId);
            } else {
                log.info("点评重写调用失败，维持原点评 - defenseId: {}", defenseId);
            }
            return comment;
        } catch (Exception e) {
            log.warn("点评接地校验异常，维持原点评: {}", e.getMessage());
            return comment;
        }
    }

    /**
     * 【N46/N49】点评重写调用：只要求模型输出一行「引用本轮答案具体词」的点评，
     * 不改评分行（分值仍由服务端管）。调用失败返回 null。
     */
    private String rewriteGroundedComment(String question, String answer, String oldComment) {
        try {
            StringBuilder p = new StringBuilder();
            p.append("【系统重写·点评必须针对本轮回答】\n");
            if (question != null && !question.isEmpty()) {
                p.append("当前题：").append(question).append("\n");
            }
            p.append("学生本轮回答：").append(answer).append("\n");
            p.append("此前生成的点评：「").append(oldComment).append("」没有引用本轮回答的具体内容（空话模板或串台幻觉），判定不合格。\n");
            p.append("请只输出一行新点评：以 点评: 开头、40字以内；必须引用学生本轮回答原文里的 1~2 个具体词（技术名词、步骤或数据），");
            p.append("基于该回答的实际内容评价（可肯定优点、可指出不足）；严禁提及本题与本轮回答之外的任何内容，严禁空话套话。");
            String resp = chatClient.prompt()
                    .user(p.toString())
                    .options(OpenAiChatOptions.builder()
                            .model(ollamaModelName)
                            .temperature(0.0)
                            .maxTokens(120)
                            .build())
                    .call()
                    .content();
            if (resp == null || resp.trim().isEmpty()) return null;
            String t = resp.trim();
            int idx = t.indexOf(AiProtocolConstants.COMMENT_TAG);
            if (idx >= 0) {
                t = t.substring(idx + AiProtocolConstants.COMMENT_TAG.length()).trim();
            }
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(0, nl).trim();
            }
            String stripped = stripTopicMarker(t);
            return (stripped == null || stripped.isEmpty()) ? null : stripped;
        } catch (Exception e) {
            log.warn("点评重写调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 放弃作答的固定零分评分记录（五维全0）【B1】只构建，落库统一走 saveScoreThenAnswer 同事务 */
    private DefenseScoreRecord buildGiveUpScoreRecord(Integer defenseId, Integer questionId, int roundNum, String comment) {
        DefenseScoreRecord record = new DefenseScoreRecord();
        record.setDefenseId(defenseId);
        record.setQuestionId(questionId);
        record.setRoundNum(roundNum);
        record.setExpressionScore(BigDecimal.ZERO);
        record.setLogicScore(BigDecimal.ZERO);
        record.setProfessionalScore(BigDecimal.ZERO);
        record.setAdaptabilityScore(BigDecimal.ZERO);
        record.setInnovationScore(BigDecimal.ZERO);
        record.setComment(comment);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    /** 追问阶段放弃作答：将该回答按0分存入 defense_answers（问题行通常已由追问登记时创建） */
    private void saveGiveUpFollowUpAnswer(Integer defenseId, List<Message> history,
                                          String userInput, String feedback, Double score) {
        try {
            // 【N5 · 2026-09-28】定位「本轮学生正在作答的那道追问」分两条路：
            // ① 优先从会话历史取上一轮 AI 提出的题（最准）；
            // ② 历史被 MAX_HISTORY_MESSAGES 裁剪时，退化为「最新登记的追问题」——
            //    每轮末尾 submitIfNewQuestion 会把模型给出的下一题登记进 defense_student_questions，
            //    所以 sort 最大的那条即本轮要答的题。
            // 旧实现在 ① 失败时直接 return：评分行落了、答案行没落（N5），
            // 导致 defense_answers 与 defense_score_record 行数对不上。
            String currentQuestion = extractQuestionFromLastAiMessage(history);
            Integer sqId;
            if (currentQuestion != null && !currentQuestion.isEmpty()) {
                DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestion);
                if (existingQuestion != null) {
                    sqId = existingQuestion.getSqId();
                } else {
                    int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                    DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                    studentQuestion.setDefenseId(defenseId);
                    studentQuestion.setQuestionId(null);
                    studentQuestion.setCustomQuestion(currentQuestion);
                    studentQuestion.setCustomStandardAnswer("");
                    studentQuestion.setQuestionType("ai");
                    studentQuestion.setSort(nextSort);
                    studentQuestion.setCreatedAt(LocalDateTime.now());
                    defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                    sqId = studentQuestion.getSqId();
                }
            } else {
                sqId = findLatestFollowUpSqId(defenseId);
                if (sqId == null) {
                    log.warn("放弃作答处理：历史已裁剪且无登记过的追问题，跳过回答落库 - defenseId: {}", defenseId);
                    return;
                }
                log.info("放弃作答处理：历史已裁剪，改用最新登记的追问题定位 - defenseId: {}, sqId: {}", defenseId, sqId);
            }
            if (sqId == null) return;

            DefenseAnswers answer = new DefenseAnswers();
            answer.setDefenseId(defenseId);
            answer.setQuestionId(null);
            answer.setSqId(sqId);
            answer.setStudentAnswer(userInput);
            answer.setFeedback(feedback);
            answer.setScore(score != null ? BigDecimal.valueOf(score) : null);
            answer.setCreatedAt(LocalDateTime.now());
            defenseAnswersMapper.insertAnswer(answer);
        } catch (Exception e) {
            log.warn("放弃作答追问回答落库失败 - defenseId: {}", defenseId, e);
        }
    }

    /**
     * 取该场答辩「最新登记的追问题」的 sqId（N5 兜底路径）。
     *
     * <p>登记表里既有纯题目、也有整段 AI 回复（两个入库点写入格式不同），
     * 用 {@link AiTextUtils#cleanQuestionText} 过滤出真正能解析成题目的记录。</p>
     */
    private Integer findLatestFollowUpSqId(Integer defenseId) {
        try {
            List<DefenseStudentQuestions> questions = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            if (questions == null || questions.isEmpty()) {
                return null;
            }
            // 已按 sort 升序，从后往前找第一条能解析出题目的记录
            for (int i = questions.size() - 1; i >= 0; i--) {
                DefenseStudentQuestions q = questions.get(i);
                if (AiTextUtils.cleanQuestionText(q.getCustomQuestion()) != null) {
                    return q.getSqId();
                }
            }
        } catch (Exception e) {
            log.warn("查找最新追问题失败 - defenseId: {}", defenseId, e);
        }
        return null;
    }

    /**
     * 答辩收尾：聚合各轮五维评分（0-50制平均），连同总结写回 defense_records，供前端答辩记录展示
     */
    @SuppressWarnings("unchecked")
    private void finishDefenseAggregation(Integer defenseId, String summary) {
        try {
            if (defenseId == null) {
                log.warn("答辩收尾：defenseId为空，跳过总分落库");
                return;
            }
            Map<String, Object> aggregated = scorePersistenceService.aggregateScores(defenseId);
            List<DefenseScoreRecord> records = (List<DefenseScoreRecord>) aggregated.get("records");
            if (records == null || records.isEmpty()) {
                log.warn("答辩收尾：无评分记录，跳过总分落库 - defenseId: {}", defenseId);
                return;
            }
            double total = 0;
            for (DefenseScoreRecord r : records) {
                total += nvl(r.getExpressionScore()) + nvl(r.getLogicScore()) + nvl(r.getProfessionalScore())
                        + nvl(r.getAdaptabilityScore()) + nvl(r.getInnovationScore());
            }
            BigDecimal finalScore = BigDecimal.valueOf(Math.round(total / records.size() * 10) / 10.0);
            defenseRecordsService.finishDefenseRecord(defenseId, finalScore, summary);
            log.info("答辩收尾完成 - defenseId: {}, 总分: {}, 轮次数: {}", defenseId, finalScore, records.size());
        } catch (Exception e) {
            log.error("答辩收尾聚合失败 - defenseId: {}", defenseId, e);
        }
    }

    private double nvl(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    /**
     * 聚合各轮评分生成总评分数文案（2026.9.15 总结带总评）："总分X/50（表达A 逻辑B 专业C 应变D 创新E）。"
     * 总分口径与 finishDefenseAggregation 一致：各轮五维之和的平均值，四舍五入 1 位小数。
     * 注意：评分为异步落库，极端情况下末轮记录可能尚未写入导致总分少算——与既有收尾聚合同口径，方向安全。
     */
    @SuppressWarnings("unchecked")
    private String buildFinalScoreText(Integer defenseId) {
        try {
            if (defenseId == null) return "";
            Map<String, Object> aggregated = scorePersistenceService.aggregateScores(defenseId);
            List<DefenseScoreRecord> records = (List<DefenseScoreRecord>) aggregated.get("records");
            if (records == null || records.isEmpty()) return "";
            double expr = 0, logic = 0, prof = 0, adap = 0, inn = 0;
            for (DefenseScoreRecord r : records) {
                expr += nvl(r.getExpressionScore());
                logic += nvl(r.getLogicScore());
                prof += nvl(r.getProfessionalScore());
                adap += nvl(r.getAdaptabilityScore());
                inn += nvl(r.getInnovationScore());
            }
            int n = records.size();
            double total = Math.round((expr + logic + prof + adap + inn) / n * 10) / 10.0;
            return "总分" + fmtScore(total) + "/50（表达" + fmtScore(expr / n)
                    + " 逻辑" + fmtScore(logic / n) + " 专业" + fmtScore(prof / n)
                    + " 应变" + fmtScore(adap / n) + " 创新" + fmtScore(inn / n) + "）。";
        } catch (Exception e) {
            log.warn("总结总评分聚合失败 - defenseId: {}, error: {}", defenseId, e.getMessage());
            return "";
        }
    }

    /** 分数展示：整数不带小数位，非整数保留 1 位（8.0→8，7.5→7.5） */
    private String fmtScore(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /** 从AI回复中提取"总结:"后的文本 */
    private String extractSummaryText(String aiResponse) {
        if (aiResponse == null) return null;
        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1) return null;
        return aiResponse.substring(pos + 3).trim();
    }

    /** 点评是否以「跑题」标记开头（[跑题] / 【跑题】 / 跑题） */
    private boolean isOffTopicMarked(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("[跑题]") || t.startsWith("【跑题】") || t.startsWith("跑题");
    }

    /**
     * 从整段回复里精确匹配“点评:跑题”。
     * 作为 {@link #isOffTopicMarked} 的补充信号：万一 comment 解析失败，兜底仍能命中。
     */
    private boolean isOffTopicMarkedInResponse(String aiResponse) {
        if (aiResponse == null) return false;
        return java.util.regex.Pattern.compile("点评[:：]\\s*[\\[【]?\\s*跑题").matcher(aiResponse).find();
    }

    /** 去掉点评开头的 [切题]/[错误]/[跑题] 标记，只把正文展示给用户 */
    private String stripTopicMarker(String comment) {
        if (comment == null) return null;
        String c = comment.trim();
        String[] markers = {"[切题]", "【切题】", "[错误]", "【错误】", "[跑题]", "【跑题】", "切题", "跑题"};
        for (String m : markers) {
            if (c.startsWith(m)) {
                return c.substring(m.length()).replaceFirst("^[:：,，。\\s]+", "").trim();
            }
        }
        return c;
    }

    /**
     * 把回复中的评分行整体改写为 0 分（五维全 0），保证前端展示与落库口径一致。
     * 兼容两种写法：带"评分:"前缀的三段式，以及无前缀的纯管道评分行。
     * 历史缺陷：只改写了"评分:xx/50..."，无前缀的纯管道行（38/50|8|7|...）原样返回，
     * 导致跑题归零后"气泡仍显示 38 分、库中已 0 分"。
     */
    private String forceZeroScoreLine(String aiResponse) {
        if (aiResponse == null) return null;
        String out = aiResponse.replaceAll("评分[:：]\\s*\\d+(?:\\.\\d+)?\\s*/\\s*50[^\\n]*", "评分:0/50|0|0|0|0|0");
        // 纯管道评分行：行首（可含空白）形如 38/50|8|7|8|7|8，整体改写为 0 分
        out = out.replaceAll("(?m)^[ \\t]*\\d+(?:\\.\\d+)?\\s*/\\s*50[ \\t]*(?:\\|[^\\n]*)?", "0/50|0|0|0|0|0");
        return out;
    }

    /**
     * 由服务端最终分值拼评分行：总分 = 五维之和，五维 clamp 到 0~10。
     * 与前端 parseSegmentFormat / parsePipeFormat 的口径保持一致。
     */
    private String buildScoreLine(Map<String, Object> scores) {
        if (scores == null) return null;
        boolean any = scores.containsKey("totalScore");
        for (String k : SCORE_KEYS) any = any || scores.containsKey(k);
        if (!any) return null;
        double[] dims = new double[SCORE_KEYS.length];
        double total = 0;
        for (int i = 0; i < SCORE_KEYS.length; i++) {
            Object v = scores.get(SCORE_KEYS[i]);
            double d = (v instanceof Number n) ? n.doubleValue() : 0.0;
            d = Math.max(0, Math.min(10, d));
            dims[i] = d;
            total += d;
        }
        StringBuilder sb = new StringBuilder("评分:").append(fmtScore(total)).append("/50");
        for (double d : dims) sb.append('|').append(fmtScore(d));
        return sb.toString();
    }

    /**
     * 统一「点评 / 评分」两行：点评用服务端最终文案、评分用服务端最终分值（总分=五维之和）。
     * 目的：让「前端气泡显示 == 落库分 == Redis 记忆」三处完全一致，
     * 杜绝模型自报总分与五维和打架（实测 defenseId 292：模型写 38/50，五维和其实只有 35）。
     */
    private String normalizeCommentAndScore(String aiResponse, String comment, Map<String, Object> scores) {
        if (aiResponse == null) return null;
        String out = aiResponse;
        if (comment != null && !comment.isEmpty()) {
            out = out.replaceFirst("(?m)^点评[:：][^\\n]*",
                    java.util.regex.Matcher.quoteReplacement("点评:" + comment));
        }
        String scoreLine = buildScoreLine(scores);
        if (scoreLine != null) {
            if (java.util.regex.Pattern.compile("(?m)^评分[:：].*$").matcher(out).find()) {
                out = out.replaceFirst("(?m)^评分[:：].*$",
                        java.util.regex.Matcher.quoteReplacement(scoreLine));
            } else if (java.util.regex.Pattern.compile("(?m)^[ \\t]*\\d+(?:\\.\\d+)?\\s*/\\s*50").matcher(out).find()) {
                out = out.replaceFirst("(?m)^[ \\t]*\\d+(?:\\.\\d+)?\\s*/\\s*50[^\\n]*",
                        java.util.regex.Matcher.quoteReplacement(scoreLine.substring("评分:".length())));
            }
        }
        return out;
    }

    /**
     * 点评中是否出现"指出错误"的措辞。命中即触发 {@link RecheckType#WRONG_ANSWER} 复核
     * （2026-09-27 前只用于影子诊断打日志；词表也同步扩到"改为/替代/应为/实际是"这类软性纠错）。
     * 词表抽成常量 {@link #ERROR_CUE_PHRASES}，后续实测补词只改常量一处。
     *
     * <p>【N50 · 2026-09-27】匹配前先剥掉 {@link #WRONG_ANSWER_NOTE}：它是 prompt 要求 [错误] 档
     * 附加的**格式后缀**，可 3B 模型常把它当成通用后缀，连 [切题] 的点评也挂在末尾
     * （298 场第 1/2/4/7 轮原始返回均为 `点评:[切题]…（有明显回答错误）`）。词表里的"错误"
     * 一旦匹配到这个词缀，每轮都会被误判为"疑似错误"而多跑一次复核（+2~5 秒），
     * 还有被复核误判降档的风险。剥离后只保留模型真正写出的纠错措辞。
     */
    private boolean containsErrorCue(String comment) {
        if (comment == null || comment.isEmpty()) return false;
        String cleaned = comment.replace(WRONG_ANSWER_NOTE, "");
        if (cleaned.isEmpty()) return false;
        for (String cue : ERROR_CUE_PHRASES) {
            if (cleaned.contains(cue)) return true;
        }
        return false;
    }

    /**
     * 明显回答错误 → 五维各减半、总分重算为五维之和（2026-09-26 半分档，2026-09-27 抽出以供复核复用）。
     * 分值一律由服务端决定，不留给模型自行减半，避免"模型减一次 + 服务端再减一次"的双重折扣。
     */
    private Map<String, Object> applyWrongAnswerHalfScore(Map<String, Object> scores) {
        Map<String, Object> halved = new java.util.HashMap<>(scores);
        double halfTotal = 0;
        for (String k : SCORE_KEYS) {
            Object v = scores.get(k);
            double d = (v instanceof Number n) ? n.doubleValue() : 0.0;
            d = Math.max(0, Math.min(10, d)) / 2.0;
            halved.put(k, d);
            halfTotal += d;
        }
        halved.put("totalScore", halfTotal);
        return halved;
    }

    /** 跑题 / 答非所问 → 五维归零、总分归零（供跑题判定与 {@link RecheckType#OFF_TARGET} 复核共用） */
    private Map<String, Object> applyZeroScore(Map<String, Object> scores) {
        Map<String, Object> zeroed = new java.util.HashMap<>(scores);
        for (String k : SCORE_KEYS) {
            zeroed.put(k, 0.0);
        }
        zeroed.put("totalScore", 0.0);
        return zeroed;
    }

    /**
     * 题目概念覆盖率：题目归一化后的字符二元组，有多少比例出现在回答里（0~1）。
     * 用于发现"答的是别的题"——295 场第 2 轮题目问"MapReduce统计PM2.5"、学生整段讲"Hadoop生态组件"，
     * 二者二元组几乎不重合。只作触发复核的嫌疑信号，不直接改分，是否归零由 OFF_TARGET 复核决定。
     * 题目取不到时返回 1.0（不触发），方向安全。
     */
    private double questionCoverage(String question, String answer) {
        Set<String> qGrams = bigramSet(normalizeForCompare(question));
        if (qGrams.isEmpty()) return 1.0;
        Set<String> aGrams = bigramSet(normalizeForCompare(answer));
        if (aGrams.isEmpty()) return 0.0;
        int hit = 0;
        for (String g : qGrams) {
            if (aGrams.contains(g)) hit++;
        }
        return (double) hit / qGrams.size();
    }

    /** 字符二元组集合：供 {@link #bigramDice} 与 {@link #questionCoverage} 共用，避免两处各写一份切分逻辑 */
    private Set<String> bigramSet(String s) {
        Set<String> set = new HashSet<>();
        if (s == null || s.isEmpty()) return set;
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        if (set.isEmpty()) set.add(s);   // 单字符文本：用自身参与比较
        return set;
    }

    /** 点评是否以「错误」标记开头（[错误] / 【错误】），表示回答了本题但内容有明显错误 */
    private boolean isWrongAnswerMarked(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("[错误]") || t.startsWith("【错误】");
    }

    /** 从整段回复里精确匹配「点评:错误」，作为 {@link #isWrongAnswerMarked} 的兜底信号 */
    private boolean isWrongAnswerMarkedInResponse(String aiResponse) {
        if (aiResponse == null) return false;
        return java.util.regex.Pattern.compile("点评[:：]\\s*[\\[【]?\\s*错误").matcher(aiResponse).find();
    }

    /** 归一化学生回答/题目文本，用于长度判定与复制粘贴比对：转小写、去空白、标点、符号、Markdown 标记 */
    private String normalizeAnswerForJudge(String input) {
        if (input == null) return "";
        return input.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** 取本轮当前题目文本：预设题阶段按序号从题库取，追问阶段从历史最后一条 AI 消息的「下一题:」提取 */
    private String getQuestionTextForRound(Integer topicId, int questionIndex,
                                           List<Integer> existingQuestionIds, List<Message> history) {
        try {
            if (questionIndex >= 0 && questionIndex < existingQuestionIds.size()) {
                return fetchPresetQuestionText(topicId, questionIndex);
            }
            return extractQuestionFromLastAiMessage(history);
        } catch (Exception e) {
            log.warn("获取本轮题目文本失败（复核跳过）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 跑题误判复核（N42）：长回答被判 0 分时，追加纠正指令重新评分一次。
     * 3B 模型对"特别注意"段的短语清单有关键词误触发倾向（实测 288 场第5轮：实质切题回答因夹带语句被判 0），
     * 明确告知"该回答包含实质内容、按切题标准评分"后，模型可恢复正常评分档位。
     * @return 重评后的模型回复；调用失败返回 null（维持原 0 分判定，方向安全）
     */
    private String rescoreSuspectedMisjudge(CharSequence fullPrompt, Integer topicId, int questionIndex,
                                            List<Integer> existingQuestionIds, List<Message> history,
                                            String userInput) {
        String question = getQuestionTextForRound(topicId, questionIndex, existingQuestionIds, history);
        return runRecheck(fullPrompt, RecheckType.OFF_TOPIC, question, userInput);
    }

    /**
     * 通用复核通道（2026-09-27 抽出）：在本轮完整 prompt 之后追加一段复核指令，让模型重新输出三段式。
     * 此前跑题复核的模型调用是写死在方法里的，新增一种复核就只能复制粘贴一整段；现在三类复核
     * （跑题误判 / 错误漏判 / 答非所问）共用这一个通道，指令文案由 {@link RecheckType} 决定。
     *
     * <p>【N54 · 2026-10-02】复核指令现在显式携带「当前题」与「学生本轮回答原文」——
     * 主 prompt 的对话摘要会把学生回答截断到 100 字，复核模型可能拿不到完整原话；
     * 2026-10-02 基线实锤（第 2 轮）：学生给出完全正确的 map/reduce 步骤，复核却把学生原话
     * 复述成"纠正"砍到 12 分。显式给原文并明令"学生原话本身就是正确做法时严禁当错误纠正"。</p>
     *
     * @param basePrompt 本轮完整 prompt（含对话摘要、判分规则、当前题、学生回答、进度行）
     * @param type       复核类型
     * @param question   当前题文本（可为 null，取不到时不注入上下文）
     * @param answer     学生本轮回答原文
     * @return 重评后的模型回复；调用失败返回 null（调用方一律维持原判定，方向安全）
     */
    private String runRecheck(CharSequence basePrompt, RecheckType type, String question, String answer) {
        try {
            String resp = chatClient.prompt()
                    .user(basePrompt + "\n" + buildRecheckInstruction(type, question, answer))
                    .options(OpenAiChatOptions.builder()
                            .model(ollamaModelName)
                            .temperature(0.0)
                            .maxTokens(320)
                            .build())
                    .call()
                    .content();
            log.info("判分复核[{}] - 模型重评: {}", type, resp);
            return resp;
        } catch (Exception e) {
            log.warn("判分复核[{}]调用失败，维持原判定: {}", type, e.getMessage());
            return null;
        }
    }

    /**
     * 按复核类型生成复核指令。沿用主 prompt 的写法（先说清事实、再给硬约束、最后固定输出格式），
     * 并且只让模型做一次判断，分值仍由服务端决定——与 2026-09-17「S5 把给分权收回服务端」同原则。
     * 【N54】三类指令前统一注入「当前题 + 学生本轮回答原文」上下文，复核不再依赖被截断的摘要。
     */
    private String buildRecheckInstruction(RecheckType type, String question, String answer) {
        StringBuilder ctx = new StringBuilder("【复核上下文·以下为权威原文，复核只依据这两段内容】\n");
        if (question != null && !question.isEmpty()) {
            ctx.append("当前题：").append(question).append("\n");
        }
        ctx.append("学生本轮回答原文：")
           .append(answer == null ? "" : answer)
           .append("\n");
        return switch (type) {
            case OFF_TOPIC -> ctx + "【系统复核】上一次判定有误：该回答长度超过20字，请重新审视——只要回答包含与【当前题】相关的"
                    + "具体概念、步骤、事实或方法，就必须按切题标准正常给分（参照判分第二步的档位），严禁再判跑题、"
                    + "严禁五维全0。请重新输出点评/评分/下一题三行。\n";
            case WRONG_ANSWER -> ctx + "【系统复核·回答正确性】请逐条对照上面的「学生本轮回答原文」做事实核查，忽略上一次点评的措辞："
                    + "检查回答里的技术概念、方法步骤、数据阈值、结论判断是否与公认事实一致。"
                    + "特别强调：如果学生回答原文描述的做法本身就是正确的，严禁把学生的原话复述成“纠正”或“应该改为”——"
                    + "那是把正确答案当错误（实测已发生）。"
                    + "若发现关键概念说反、方法用错、事实或数据错误、结论错误、把无关技术硬套本题，"
                    + "点评必须以[错误]开头，先一句话点明【具体错在哪里】（不要写“表述不够清晰”这类套话），末尾附（有明显回答错误），"
                    + "分值按判分第二步给，不要自行减半；若逐条核查后确实没有事实性错误，维持[切题]并按原档位给分。"
                    + "请重新输出点评/评分/下一题三行。\n";
            case OFF_TARGET -> ctx + "【系统复核·是否回应本题】请只判断一件事：学生这段回答是否在正面回应【当前题】所问的内容？"
                    + "注意区分——回答整段在讲别的技术话题、或实际是在回答本场前面问过的另一道题"
                    + "（例如题目问“如何用MapReduce统计PM2.5”，学生却整段讲“Hadoop生态组件”），都属于【没有回应本题】。"
                    + "若确实没有回应本题，点评必须以[跑题]开头并说明学生答的是另一道题，评分固定为 0/50|0|0|0|0|0；"
                    + "若回答确实在回应本题（哪怕不完整、哪怕有错误），严禁判[跑题]，按判分第一步正常处理。"
                    + "请重新输出点评/评分/下一题三行。\n";
        };
    }

    private String extractQuestionFromLastAiMessage(List<Message> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                String q = extractQuestionFromAiText(((AssistantMessage) msg).getText());
                if (q != null && !q.isEmpty()) return q;
                break;
            }
        }
        return null;
    }

    /**
     * 从单条 AI 回复文本中提取题目。
     *
     * <p>实现统一收敛在 {@link AiTextUtils}：答辩主流程、续答回灌会话记忆、
     * 防作弊比对已问题目三处共用同一份解析（原先各写一份，改一处漏两处）。
     * 追问通常是"请说明HBase的读写流程"这类陈述句、不带问号，
     * 只认含问号行会导致追问轮取不到题目（N5 的历史根因），解析顺序已覆盖该场景。</p>
     */
    private String extractQuestionFromAiText(String text) {
        return AiTextUtils.extractQuestionFromAiText(text);
    }

    private DefenseStudentQuestions findExistingStudentQuestion(Integer defenseId, String question) {
        try {
            List<DefenseStudentQuestions> questions = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            if (questions != null) {
                for (DefenseStudentQuestions q : questions) {
                    if (q.getCustomQuestion() != null && q.getCustomQuestion().equals(question)) return q;
                }
            }
        } catch (Exception e) {
            log.warn("查找已有问题时发生异常: {}", e.getMessage());
        }
        return null;
    }

    private void submitIfNewQuestion(Integer defenseId, String question) {
        try {
            DefenseStudentQuestions existing = findExistingStudentQuestion(defenseId, question);
            if (existing != null) return;
            int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
            DefenseStudentQuestions sq = new DefenseStudentQuestions();
            sq.setDefenseId(defenseId);
            sq.setQuestionId(null);
            sq.setCustomQuestion(question);
            sq.setCustomStandardAnswer("");
            sq.setQuestionType("ai");
            sq.setSort(nextSort);
            sq.setCreatedAt(LocalDateTime.now());
            defenseStudentQuestionsMapper.insertStudentQuestion(sq);
        } catch (Exception e) {
            log.warn("保存额外问题时发生异常", e);
        }
    }

    private String summary(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【总结】")) return null;
        int start = aiResponse.indexOf("【总结】") + 4;
        int end = aiResponse.length();
        if (aiResponse.contains("【视频分析】")) end = aiResponse.indexOf("【视频分析】");
        else if (aiResponse.contains("【报告分析】")) end = aiResponse.indexOf("【报告分析】");
        else if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private String videoAnalysis(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【视频分析】")) return null;
        int start = aiResponse.indexOf("【视频分析】") + 6;
        int end = aiResponse.length();
        if (aiResponse.contains("【报告分析】")) end = aiResponse.indexOf("【报告分析】");
        else if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private String reportAnalysis(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【报告分析】")) return null;
        int start = aiResponse.indexOf("【报告分析】") + 6;
        int end = aiResponse.length();
        if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private Flux<String> handleFallback(String userId, Integer topicId, String prompt, String reason,
                                         String sessionId, String userInput) {
        String fallbackPrompt = reason + "，直接回答问题。\n\n" + prompt;
        return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, topicId, fallbackPrompt, sessionId, userInput, 0));
    }

    // ==================== 视频辅助模式 Prompt ====================

    @SuppressWarnings("unchecked")
    private StringBuilder buildVideoAssistModePrompt(Object topicData, Integer topicId, String prompt) {
        StringBuilder contextPrompt = new StringBuilder();
        contextPrompt.append("【答辩主题信息】\n");
        contextPrompt.append("主题 ID: ").append(topicId).append("\n");

        if (topicData instanceof TopicDto) {
            TopicDto topic = (TopicDto) topicData;
            if (topic.getTopicName() != null) {
                contextPrompt.append("主题名称：").append(topic.getTopicName()).append("\n");
            }
            if (topic.getTopicDescription() != null) {
                contextPrompt.append("主题描述：").append(topic.getTopicDescription()).append("\n");
            }
        }

        contextPrompt.append("\n【系统提示】\n");
        contextPrompt.append("你是一名专业的答辩助手。\n");
        contextPrompt.append("当前答辩主题暂时没有预设问题，请结合题目和视频内容进行回答。\n");
        contextPrompt.append("请务必使用 getVideoContentByTopicId 工具查询 topicId=").append(topicId);
        contextPrompt.append(" 的视频文字内容（包括 PPT 文字和演讲内容）来获取详细信息。\n");
        contextPrompt.append("基于查询到的视频内容，为用户提供专业、准确的回答。\n\n");
        contextPrompt.append("【用户回答】\n").append(prompt);

        return contextPrompt;
    }
}
