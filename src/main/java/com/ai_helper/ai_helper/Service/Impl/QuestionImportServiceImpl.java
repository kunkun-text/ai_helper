package com.ai_helper.ai_helper.Service.Impl;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReader;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import com.ai_helper.ai_helper.Service.QuestionImportService;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.mapper.RegisterMapper;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.vo.QuestionImportResultVo;
import com.ai_helper.ai_helper.util.CsvUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 教师端题库批量导入实现（N10 · 2026-10-08）。
 *
 * <p>流程：校验登录身份与课题归属 → 限制文件大小 → 解析 CSV → 逐行校验 → （覆盖模式则清空原题目）→ 逐条入库。
 * 解析与校验在写库之前完成，因此「行数超限」这类错误不会先删掉原题目再报错（事务内也不会留下半截数据）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionImportServiceImpl implements QuestionImportService {

    /** 单个文件大小上限：题目 CSV 正常只有几十 KB，2MB 足够，防止超大文件占内存 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 单次导入条数上限（逐条 insert，太多会长时间占用连接） */
    private static final int MAX_ROWS = 500;

    /** 题目长度上限 */
    private static final int MAX_QUESTION_LENGTH = 1000;

    /** 标准答案长度上限 */
    private static final int MAX_ANSWER_LENGTH = 5000;

    /** 跳过原因最多回传条数 */
    private static final int MAX_REASON_RETURN = 20;

    /** 注释行前缀：模板里的说明行以 # 开头，导入时忽略 */
    private static final String COMMENT_PREFIX = "#";

    /** 允许的表头（第一行命中则不当作题目） */
    private static final Set<String> HEADER_CELLS = Set.of("题目", "问题", "question", "标准答案", "答案", "answer");

    private final DefenseTopicsMapper defenseTopicsMapper;
    private final RegisterMapper registerMapper;

    @Override
    public byte[] buildTemplateCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvUtils.BOM);
        sb.append("# 题库批量导入模板（AI 答辩辅助系统）").append(CsvUtils.CRLF);
        sb.append("# 填写规则：第一列＝题目（必填）；第二列＝标准答案（可留空）").append(CsvUtils.CRLF);
        sb.append("# 以 # 开头的行是注释，导入时会忽略；下面这行表头也会被忽略").append(CsvUtils.CRLF);
        sb.append("# 单次最多 ").append(MAX_ROWS).append(" 条；导入方式可选「追加」或「覆盖」").append(CsvUtils.CRLF);
        sb.append("# 保存时请选择「CSV UTF-8」编码，否则中文会乱码").append(CsvUtils.CRLF);
        sb.append(CsvUtils.joinRow("题目", "标准答案")).append(CsvUtils.CRLF);
        sb.append(CsvUtils.joinRow("什么是HDFS的小文件问题？", "大量小文件会压垮NameNode内存，读取时元数据开销大"))
                .append(CsvUtils.CRLF);
        sb.append(CsvUtils.joinRow("MapReduce的Shuffle过程包含哪些阶段？", "Map端spill与merge，Reduce端copy、merge、reduce"))
                .append(CsvUtils.CRLF);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuestionImportResultVo importCsv(String loginUserNumber, Integer topicId, boolean replace, MultipartFile file) {
        // 1. 参数与身份
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请先选择要导入的 CSV 文件");
        }
        if (topicId == null) {
            throw new BusinessException("请先选择要导入到哪个课题");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException("文件过大（上限 2MB），请检查是否选错了文件");
        }
        Integer teacherId = resolveTeacherId(loginUserNumber);
        Integer ownerTeacherId = defenseTopicsMapper.selectTeacherIdByTopicId(topicId);
        if (ownerTeacherId == null) {
            throw new BusinessException("未找到该课题");
        }
        if (!ownerTeacherId.equals(teacherId)) {
            log.warn("题库导入越权拦截 - topicId: {}, 登录教师: {}", topicId, teacherId);
            throw new BusinessException("无权修改他人的课题");
        }

        // 2. 解析 + 校验（全部在写库之前完成）
        List<DefenseQuestions> toInsert = new ArrayList<>();
        List<String> skippedReasons = new ArrayList<>();
        int skipped = 0;

        Set<String> seen = new HashSet<>();
        if (!replace) {
            // 追加模式下与库里已有题目去重（同一课题内允许重名是没有意义的）
            List<DefenseQuestions> existing = defenseTopicsMapper.getDefenseQuestionById(topicId);
            if (existing != null) {
                for (DefenseQuestions q : existing) {
                    seen.add(normalize(q.getQuestion()));
                }
            }
        }

        int dataRowNo = 0;
        boolean headerChecked = false;
        for (CsvRow row : readRows(file)) {
            List<String> cells = row.getRawList();
            String question = cell(cells, 0);
            String answer = cell(cells, 1);

            if (!headerChecked) {
                headerChecked = true;
                // 首行可能是表头（也可能直接被 Excel 写成「题目,标准答案」）
                if (isHeaderRow(question, answer)) {
                    continue;
                }
            }
            if (question.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            if (question.isEmpty() && answer.isEmpty()) {
                continue;
            }

            dataRowNo++;
            if (dataRowNo > MAX_ROWS) {
                throw new BusinessException("单次最多导入 " + MAX_ROWS + " 条题目，请拆分文件后重试");
            }
            if (question.isEmpty()) {
                skipped++;
                addReason(skippedReasons, "第 " + dataRowNo + " 行：题目为空");
                continue;
            }
            if (question.length() > MAX_QUESTION_LENGTH) {
                skipped++;
                addReason(skippedReasons, "第 " + dataRowNo + " 行：题目超过 " + MAX_QUESTION_LENGTH + " 字");
                continue;
            }
            if (answer.length() > MAX_ANSWER_LENGTH) {
                skipped++;
                addReason(skippedReasons, "第 " + dataRowNo + " 行：标准答案超过 " + MAX_ANSWER_LENGTH + " 字");
                continue;
            }
            if (!seen.add(normalize(question))) {
                skipped++;
                addReason(skippedReasons, "第 " + dataRowNo + " 行：题目重复（与已有题目或本文件前面某行相同）");
                continue;
            }

            DefenseQuestions entity = new DefenseQuestions();
            entity.setTopicId(topicId);
            entity.setTeacherId(teacherId);
            entity.setQuestionType("teacher");
            entity.setQuestion(question);
            entity.setStandardAnswer(answer.isEmpty() ? null : answer);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            toInsert.add(entity);
        }

        if (toInsert.isEmpty()) {
            throw new BusinessException(skipped > 0
                    ? "没有可导入的题目（" + skipped + " 行被跳过），请检查文件内容与格式"
                    : "文件里没有读到题目，请确认第一列是题目");
        }

        // 3. 写库（覆盖模式先清空该课题原题目）
        if (replace) {
            int removed = defenseTopicsMapper.deleteQuestionsByTopicId(topicId);
            log.info("覆盖导入：已清空课题原题目 - topicId: {}, 清除条数: {}", topicId, removed);
        }
        for (DefenseQuestions entity : toInsert) {
            defenseTopicsMapper.addDefenseQuestion(entity);
        }

        QuestionImportResultVo result = new QuestionImportResultVo();
        result.setImported(toInsert.size());
        result.setSkipped(skipped);
        result.setReplaced(replace);
        result.setSkippedReasons(skippedReasons);
        log.info("题库批量导入完成 - topicId: {}, 导入: {} 条, 跳过: {} 条, 覆盖模式: {}",
                topicId, toInsert.size(), skipped, replace);
        return result;
    }

    // ==================== 内部工具 ====================

    /** 用 hutool 的 CSV 解析器读全部行（自动处理引号包裹、字段内逗号/换行）；先剥掉 BOM */
    private List<CsvRow> readRows(MultipartFile file) {
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取题库 CSV 失败", e);
            throw new BusinessException("文件读取失败，请重新选择后重试");
        }
        if (text.startsWith(CsvUtils.BOM)) {
            text = text.substring(CsvUtils.BOM.length());
        }
        try {
            CsvReader reader = CsvUtil.getReader();
            CsvData data = reader.read(new StringReader(text));
            return data.getRows();
        } catch (Exception e) {
            log.warn("解析题库 CSV 失败", e);
            throw new BusinessException("文件不是有效的 CSV，请用模板另存为「CSV UTF-8」后重试");
        }
    }

    private String cell(List<String> cells, int index) {
        if (cells == null || index >= cells.size() || cells.get(index) == null) {
            return "";
        }
        return cells.get(index).trim();
    }

    private boolean isHeaderRow(String question, String answer) {
        return HEADER_CELLS.contains(question.toLowerCase()) || HEADER_CELLS.contains(answer.toLowerCase());
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    private void addReason(List<String> reasons, String reason) {
        if (reasons.size() < MAX_REASON_RETURN) {
            reasons.add(reason);
        }
    }

    /** 登录态工号 → users.user_id（与 DefenseController.resolveCurrentTeacherId 同一口径） */
    private Integer resolveTeacherId(String loginUserNumber) {
        if (loginUserNumber == null || loginUserNumber.trim().isEmpty()) {
            throw new BusinessException("登录信息已失效，请重新登录");
        }
        UserDto teacher = registerMapper.selectByUserNumber(loginUserNumber.trim());
        if (teacher == null || teacher.getId() == null || teacher.getId().trim().isEmpty()) {
            throw new BusinessException("登录教师身份解析失败，请重新登录");
        }
        try {
            return Integer.parseInt(teacher.getId().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("登录教师身份解析失败，请重新登录");
        }
    }
}
