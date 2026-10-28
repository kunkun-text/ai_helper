package com.ai_helper.ai_helper.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 【2026-10-28】视频综合评价列（{@code video_summary}）的启动自检。
 *
 * <p><b>为什么需要它：</b>详情 SQL 已直接查 {@code dr.video_summary}，而该列来自手工执行的
 * {@code docs/ddl_video_summary.sql}。漏执行时<b>教师端/学生端的答辩详情接口会全部 500</b>，
 * 前端只显示「服务器处理失败」，从界面看不出是缺列（与 2026-10-25 的 material_verdict 同款事故）。</p>
 *
 * <p>自检只读 {@code information_schema}，<b>不自动改库</b>；缺列时打一条醒目 ERROR 并附执行命令。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VideoSummarySchemaCheck implements ApplicationRunner {

    private static final String SQL = "SELECT COUNT(*) FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'defense_records' "
            + "AND COLUMN_NAME = 'video_summary'";

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.video-analysis.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(SQL, Integer.class);
            if (count != null && count > 0) {
                log.info("视频综合评价列已就绪（defense_records.video_summary）");
                return;
            }
            log.error("""

                    ================ 视频综合评价列缺失 ================
                    defense_records 缺少 video_summary。
                    教师端/学生端的答辩详情接口会返回「服务器处理失败」
                    （日志里表现为 Unknown column 'dr.video_summary'）。
                    请执行：mysql -uroot -p ai_helper < docs/ddl_video_summary.sql
                    ===================================================
                    """);
        } catch (Exception e) {
            // 自检失败不影响启动：连不上 information_schema 时运行期报错信息已经足够定位
            log.warn("视频综合评价列自检失败（不影响启动）- 原因: {}", e.getMessage());
        }
    }
}
