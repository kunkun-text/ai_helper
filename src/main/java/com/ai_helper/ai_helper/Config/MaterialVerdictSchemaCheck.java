package com.ai_helper.ai_helper.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 【2026-10-25】材料裁定列的启动自检。
 *
 * <p><b>为什么需要它：</b>列表/详情 SQL 直接查 {@code dr.material_verdict} 等三列，
 * 而这三列来自手工执行的 DDL（{@code docs/ddl_material_verdict.sql}）。
 * 一旦部署时漏执行，<b>学生端与教师端的列表接口会全部 500</b>，
 * 前端只显示「服务器处理失败」，从界面完全看不出是缺列——
 * 2026-10-25 就是这么踩的（日志里才看到 {@code Unknown column 'dr.material_verdict'}）。
 *
 * <p>自检只读 {@code information_schema}，<b>不自动改库</b>（启动期改表风险太大），
 * 缺列时打一条醒目的 ERROR 并附上要执行的命令，让问题在启动日志里就暴露。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MaterialVerdictSchemaCheck implements ApplicationRunner {

    private static final String SQL = "SELECT COUNT(*) FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'defense_records' "
            + "AND COLUMN_NAME IN ('material_verdict','material_verdict_type','material_verdict_reason')";

    private static final int EXPECTED_COLUMNS = 3;

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.material-verdict.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(SQL, Integer.class);
            if (count != null && count >= EXPECTED_COLUMNS) {
                log.info("材料裁定列已就绪（defense_records.material_verdict / _type / _reason）");
                return;
            }
            log.error("""

                    ================ 材料裁定列缺失 ================
                    defense_records 缺少 material_verdict / material_verdict_type / material_verdict_reason。
                    学生端与教师端的答辩记录列表、详情接口会返回「服务器处理失败」
                    （日志里表现为 Unknown column 'dr.material_verdict'）。
                    请执行：mysql -uroot -p ai_helper < docs/ddl_material_verdict.sql
                    ===============================================
                    """);
        } catch (Exception e) {
            // 自检失败不影响启动：连不上 information_schema 时运行期报错信息已经足够定位
            log.warn("材料裁定列自检失败（不影响启动）- 原因: {}", e.getMessage());
        }
    }
}
