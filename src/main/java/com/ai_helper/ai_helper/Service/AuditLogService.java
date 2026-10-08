package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.entity.DefenseAuditLog;

import java.util.List;

/**
 * 答辩场次审计日志（N15 · 2026-10-08）。
 *
 * <p>每场答辩「开始 / 续答 / 结束」各落一条记录，用于事后复盘
 * （轮次对不上、什么时候被续答、结束时的总分是多少）。</p>
 *
 * <p><b>写入一律尽力而为</b>：审计只是旁路信息，任何异常都必须被吞掉，
 * 绝不允许因为审计写失败而影响答辩主流程（与「录音归档」同一原则）。</p>
 */
public interface AuditLogService {

    /** 事件：新建答辩并开始 */
    String EVENT_START = "START";

    /** 事件：按用户选择续答既有场次 */
    String EVENT_RESUME = "RESUME";

    /** 事件：答辩结束并汇总总分 */
    String EVENT_FINISH = "FINISH";

    /**
     * 记录一条答辩事件（失败只记日志，不抛出）。
     */
    void logDefenseEvent(Integer defenseId, Integer userId, Integer topicId,
                         String event, Integer roundNum, String detail);

    /** 取某场答辩的审计轨迹（时间正序） */
    List<DefenseAuditLog> listByDefenseId(Integer defenseId);
}
