package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.AuditLogService;
import com.ai_helper.ai_helper.mapper.DefenseAuditLogMapper;
import com.ai_helper.ai_helper.pojo.entity.DefenseAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 答辩场次审计日志实现（N15 · 2026-10-08）。
 *
 * <p>单条 insert，不参与答辩事务：审计记录必须「已发生的就留下」，不能因为主流程回滚而消失。
 * 因此本类不加 {@code @Transactional}，且异常一律吞掉（只记日志）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    /** detail 字段长度上限（varchar(500)），超长截断避免写库报错 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private final DefenseAuditLogMapper auditLogMapper;

    @Override
    public void logDefenseEvent(Integer defenseId, Integer userId, Integer topicId,
                                String event, Integer roundNum, String detail) {
        try {
            DefenseAuditLog record = new DefenseAuditLog();
            record.setDefenseId(defenseId);
            record.setUserId(userId);
            record.setTopicId(topicId);
            record.setEvent(event);
            record.setRoundNum(roundNum);
            record.setDetail(truncate(detail));
            record.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insertAuditLog(record);
            log.debug("答辩审计已记录 - defenseId: {}, event: {}, round: {}", defenseId, event, roundNum);
        } catch (Exception e) {
            // 审计失败绝不影响答辩；只提示可能缺少 defense_audit_log 表
            log.warn("答辩审计记录写入失败（不影响答辩流程，若提示表不存在请执行 docs/ddl_defense_audit_log.sql） - defenseId: {}, event: {}",
                    defenseId, event, e);
        }
    }

    @Override
    public List<DefenseAuditLog> listByDefenseId(Integer defenseId) {
        if (defenseId == null) {
            return Collections.emptyList();
        }
        try {
            List<DefenseAuditLog> list = auditLogMapper.selectByDefenseId(defenseId);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.warn("查询答辩审计记录失败 - defenseId: {}", defenseId, e);
            return Collections.emptyList();
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_DETAIL_LENGTH ? text.substring(0, MAX_DETAIL_LENGTH) : text;
    }
}
