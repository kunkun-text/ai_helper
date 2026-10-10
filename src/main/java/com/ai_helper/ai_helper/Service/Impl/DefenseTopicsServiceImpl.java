package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class DefenseTopicsServiceImpl implements DefenseTopicsService {

    @Autowired
    private DefenseTopicsMapper defenseTopicsMapper;

    /**
     * 新增课题 + 题目（多表写入）。
     *
     * <p>【N20 · 2026-09-28】原实现两个问题：① {@code @Transactional} 形同虚设 ——
     * catch 住异常后 return 错误码，异常没抛出去、事务照样提交，插入一半的课题会留在库里；
     * ② 把 {@code e.getMessage()}（可能含 SQL 细节）直接回给前端。
     * 现在不捕获异常：写库失败自然上抛 → 事务回滚 → GlobalExceptionHandler 返回统一文案。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> addDefense(EditDefenseDto editDefenseDto) {
        // 前置校验：还未写库，直接返回错误即可
        // 【B3 · 2026-10-05】解析失败（null）必须拒绝，旧实现会把 null 拼成字符串 "null" 落库
        if (editDefenseDto.getDefenseTimeAsLocalDate() == null) {
            return Result.error("答辩时间不能为空，且格式必须为 yyyy-MM-dd");
        }

        // 1. 添加到 defense_topics 表
        DefenseTopics defenseTopics = new DefenseTopics();
        defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
        defenseTopics.setTopicName(editDefenseDto.getTopicName());
        defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());
        defenseTopics.setDefenseTime(editDefenseDto.getDefenseTimeAsLocalDateTime().toString());
        defenseTopics.setCreatedAt(LocalDateTime.now());
        defenseTopics.setUpdatedAt(LocalDateTime.now());

        defenseTopicsMapper.addDefense(defenseTopics);
        log.info("新增答辩课题 - topicId: {}, 名称: {}", defenseTopics.getTopicId(), defenseTopics.getTopicName());

        // 2. 批量添加题目（与课题同在一个事务：中途失败整体回滚，不留半个课题）
        if (editDefenseDto.getQuestions() != null && !editDefenseDto.getQuestions().isEmpty()) {
            for (EditDefenseDto.DefenseQuestionItem item : editDefenseDto.getQuestions()) {
                defenseTopicsMapper.addDefenseQuestion(buildQuestion(item, editDefenseDto, defenseTopics.getTopicId()));
            }
            log.info("新增答辩题目 {} 道 - topicId: {}",
                    editDefenseDto.getQuestions().size(), defenseTopics.getTopicId());
        }

        return Result.success("添加成功");
    }

    @Override
    public Result<PageResult<DefenseTopics>> getAllDefense(int pageNum, int pageSize) {
        // 参数校验
        if (pageNum <= 0) {
            pageNum = 1;
        }
        if (pageSize <= 0) {
            pageSize = 10;
        }

        // 计算偏移量
        int offset = (pageNum - 1) * pageSize;

        // 查询数据
        List<DefenseTopics> list = defenseTopicsMapper.selectAllDefense(offset, pageSize);
        long total = defenseTopicsMapper.countAllDefense();

        // 构建分页结果
        PageResult<DefenseTopics> pageResult = new PageResult<>(list, total, pageNum, pageSize);

        return Result.success(pageResult);
    }


    /**
     * 修改课题 + 差量更新题目。
     *
     * <p>【2026-10-10 · 差量更新】旧实现「先清空题目、再重建」存在数据丢失隐患：
     * {@code defense_answers.question_id} 外键 ON DELETE CASCADE —— 只要保存一次编辑
     * （哪怕只改课题名、题目原样未动），全部题目被删除的同时，历史作答行会被级联删除
     * （评分行无外键，留下孤儿）。现改为按题干文本差量比对：</p>
     *
     * <ul>
     *   <li>文本未变的题：保留原 {@code question_id}，题干/标准答案有微调则原位 UPDATE；</li>
     *   <li>新文本：INSERT；</li>
     *   <li>从提交列表消失的题：无作答引用才允许 DELETE，有作答则<b>整体拒绝</b>并明确报错。</li>
     * </ul>
     *
     * <p>比对与守卫全部在任何写库之前完成，校验失败零写入；事务语义不变（N20），
     * 任一步写库失败整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> editDefense(EditDefenseDto editDefenseDto) {
        // 【B3】日期解析失败必须拒绝，禁止把 "null" 写库
        if (editDefenseDto.getDefenseTimeAsLocalDate() == null) {
            return Result.error("答辩时间不能为空，且格式必须为 yyyy-MM-dd");
        }
        editDefenseDto.setUpdatedAt(LocalDateTime.now());

        // ===== 第一步：差量比对与删除守卫（只读，任何写库之前） =====
        List<DefenseQuestions> existing = defenseTopicsMapper.selectTeacherQuestionsByTopicId(editDefenseDto.getTopicId());
        Map<String, DefenseQuestions> existingByQuestion = new HashMap<>();
        if (existing != null) {
            for (DefenseQuestions q : existing) {
                existingByQuestion.putIfAbsent(normalizeQuestionText(q.getQuestion()), q);
            }
        }

        List<DefenseQuestions> toInsert = new ArrayList<>();
        // key = 保留的原 question_id，value = 提交的题目项（题干可能有微调，仍保留原 ID）
        Map<Integer, EditDefenseDto.DefenseQuestionItem> toUpdate = new LinkedHashMap<>();
        Set<Integer> keptIds = new HashSet<>();
        List<EditDefenseDto.DefenseQuestionItem> items = editDefenseDto.getQuestions();
        if (items != null) {
            for (EditDefenseDto.DefenseQuestionItem item : items) {
                // 空题干跳过（前端已拦截，这里兜底；与旧实现直接忽略空白项行为一致）
                if (item == null || item.getQuestion() == null || item.getQuestion().trim().isEmpty()) {
                    continue;
                }
                DefenseQuestions old = existingByQuestion.get(normalizeQuestionText(item.getQuestion()));
                if (old != null) {
                    // 保留原 question_id：历史作答/评分与题库的关联不断链
                    keptIds.add(old.getQuestionId());
                    String submittedAnswer = (item.getStandardAnswer() == null || item.getStandardAnswer().trim().isEmpty())
                            ? null : item.getStandardAnswer();
                    boolean changed = !old.getQuestion().equals(item.getQuestion())
                            || !Objects.equals(old.getStandardAnswer(), submittedAnswer);
                    if (changed) {
                        toUpdate.put(old.getQuestionId(), item);
                    }
                } else {
                    toInsert.add(buildQuestion(item, editDefenseDto, editDefenseDto.getTopicId()));
                }
            }
        }

        // 从提交列表消失的题：无作答引用才允许删，有作答则整体拒绝（不删库、不脱钩）
        if (existing != null) {
            for (DefenseQuestions old : existing) {
                if (keptIds.contains(old.getQuestionId())) {
                    continue;
                }
                int refCount = defenseTopicsMapper.countAnswersByQuestionId(old.getQuestionId());
                if (refCount > 0) {
                    String q = old.getQuestion();
                    String brief = (q != null && q.length() > 20) ? q.substring(0, 20) + "…" : q;
                    log.warn("编辑课题拒绝：题目仍有作答引用不可删除 - topicId: {}, questionId: {}, 引用数: {}",
                            editDefenseDto.getTopicId(), old.getQuestionId(), refCount);
                    return Result.error("题目「" + brief + "」已有 " + refCount + " 条学生作答，不能删除；"
                            + "请保留该题，或另建新课题");
                }
            }
        }

        // ===== 第二步：写库（课题元数据 → 删被移除题 → 原位更新保留题 → 插入新题） =====
        // 1. 修改 defense_topics 表
        DefenseTopics defenseTopics = new DefenseTopics();
        defenseTopics.setTopicId(editDefenseDto.getTopicId());
        defenseTopics.setTeacherId(editDefenseDto.getTeacherId());
        defenseTopics.setTopicName(editDefenseDto.getTopicName());
        defenseTopics.setTopicDescription(editDefenseDto.getTopicDescription());
        defenseTopics.setDefenseTime(editDefenseDto.getDefenseTimeAsLocalDateTime().toString());
        defenseTopics.setUpdatedAt(LocalDateTime.now());

        defenseTopicsMapper.editDefense(defenseTopics);

        // 2. 删除被移除且无作答的题（有作答的在上面守卫处已整体拒绝，走不到这里）
        if (existing != null) {
            for (DefenseQuestions old : existing) {
                if (!keptIds.contains(old.getQuestionId())) {
                    defenseTopicsMapper.deleteDefenseQuestionById(old.getQuestionId());
                }
            }
        }

        // 3. 原位更新保留题（题干/标准答案有变化才进来）
        for (Map.Entry<Integer, EditDefenseDto.DefenseQuestionItem> entry : toUpdate.entrySet()) {
            DefenseQuestions updated = new DefenseQuestions();
            updated.setQuestionId(entry.getKey());
            updated.setQuestion(entry.getValue().getQuestion());
            String submittedAnswer = entry.getValue().getStandardAnswer();
            updated.setStandardAnswer((submittedAnswer == null || submittedAnswer.trim().isEmpty())
                    ? null : submittedAnswer);
            updated.setUpdatedAt(LocalDateTime.now());
            defenseTopicsMapper.updateDefenseQuestion(updated);
        }

        // 4. 插入新题
        for (DefenseQuestions question : toInsert) {
            defenseTopicsMapper.addDefenseQuestion(question);
        }

        log.info("修改答辩课题完成 - topicId: {}, 保留: {}, 更新: {}, 新增: {}, 删除: {}",
                editDefenseDto.getTopicId(), keptIds.size(), toUpdate.size(),
                toInsert.size(), (existing == null ? 0 : existing.size()) - keptIds.size());
        return Result.success("修改成功");
    }

    /**
     * 题干归一化（editDefense 差量比对用）：仅去首尾空白。
     * 与 N10 导入去重的 normalize（去全部空白/标点）刻意不同——这里「文本没动」的判定
     * 要保守：标点差异视为两道题，宁可多插新题也不误合并。
     */
    private String normalizeQuestionText(String text) {
        return text == null ? "" : text.trim();
    }

    @Override
    public Result<List<DefenseQuestions>> getDefenseQuestionById(Integer topicId) {
        List<DefenseQuestions> list = defenseTopicsMapper.getDefenseQuestionById(topicId);
        return Result.success(list);
    }

    @Override
    public Result<Object> deleteDefenseTopics(Integer topicId) {
        int result = defenseTopicsMapper.deleteDefenseTopics(topicId);
        return result > 0 ?
                Result.success("删除成功") :
                Result.error("删除失败");
    }

    @Override
    public Result<Object> getTopicById(Integer topicId) {
        try {
            TopicDto topic = defenseTopicsMapper.getTopicById(topicId);
            if (topic != null) {
                return Result.success(topic);
            } else {
                return Result.error("未找到该答辩主题");
            }
        } catch (Exception e) {
            // 【N31】不再 e.printStackTrace() + 把 SQL 细节回传前端
            log.error("查询答辩主题失败 - topicId: {}", topicId, e);
            return Result.error("查询失败，请稍后重试");
        }
    }

    /** 组装一道题目（新增与修改两处复用，避免字段遗漏） */
    private DefenseQuestions buildQuestion(EditDefenseDto.DefenseQuestionItem item,
                                           EditDefenseDto editDefenseDto, Integer topicId) {
        DefenseQuestions question = new DefenseQuestions();
        question.setTopicId(topicId);
        question.setTeacherId(editDefenseDto.getTeacherId());
        question.setQuestionType(item.getQuestionType());
        question.setQuestion(item.getQuestion());
        question.setStandardAnswer(item.getStandardAnswer());
        question.setCreatedAt(LocalDateTime.now());
        question.setUpdatedAt(LocalDateTime.now());
        return question;
    }
}
