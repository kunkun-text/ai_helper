package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 答辩场次审计日志（N15 · 2026-10-08）。表 {@code defense_audit_log}，建表见 docs/ddl_defense_audit_log.sql。
 */
@Mapper
public interface DefenseAuditLogMapper {

    /** 写入一条审计记录 */
    int insertAuditLog(DefenseAuditLog log);

    /** 按答辩场次取审计记录，按时间正序 */
    List<DefenseAuditLog> selectByDefenseId(@Param("defenseId") Integer defenseId);
}
