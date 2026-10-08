package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.vo.QuestionImportResultVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * 教师端题库批量导入（N10 · 2026-10-08）。
 *
 * <p>用 CSV 而不是 Excel：项目里没有 POI，为了一个导入功能引入 POI 不划算；
 * CSV 用 Excel/WPS 都能直接另存导出，教师端从聊天记录选文件（{@code wx.chooseMessageFile}）即可上传。</p>
 *
 * <p><b>鉴权</b>：身份只信登录态；导入前校验目标课题属于当前教师，防止往别人的课题里灌题。</p>
 */
public interface QuestionImportService {

    /**
     * 生成导入模板 CSV（UTF-8 BOM + CRLF），供教师下载后填写。
     */
    byte[] buildTemplateCsv();

    /**
     * 批量导入题库。
     *
     * @param loginUserNumber 登录态工号（教师）
     * @param topicId         目标课题
     * @param replace         true = 覆盖导入（先清空该课题原题目）；false = 追加导入
     * @param file            上传的 CSV 文件
     * @return 导入结果（成功/跳过条数与原因）
     */
    QuestionImportResultVo importCsv(String loginUserNumber, Integer topicId, boolean replace, MultipartFile file);
}
