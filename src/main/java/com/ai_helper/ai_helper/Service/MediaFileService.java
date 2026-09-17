package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.enums.MediaKind;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 附件（答辩视频 / 答辩报告）的统一读写入口。
 *
 * <p>视频与报告原先各写一套上传代码，这里把「校验 → 落盘 → 写库 → 清理旧文件」
 * 收敛为一处，新增文件类型只需扩 {@link MediaKind} 枚举。</p>
 *
 * <p>所有方法的「当前用户」一律取自登录态，不接受前端传入的 userId，
 * 因此改参数无法读写他人文件。</p>
 */
public interface MediaFileService {

    /**
     * 单次直传保存（小视频与报告共用这条路径）。
     *
     * @return url（对外 URL 路径）/ fileName / fileSize / kind
     */
    Map<String, Object> saveWholeFile(String loginUserNumber, Integer topicId, MediaKind kind, MultipartFile file);

    /**
     * 读取当前附件地址。
     *
     * @return 对外 URL 路径；未上传返回 null
     */
    String getFileUrl(String loginUserNumber, Integer topicId, MediaKind kind);

    /**
     * 删除当前附件（同时清理磁盘文件）。
     */
    void deleteFile(String loginUserNumber, Integer topicId, MediaKind kind);

    /**
     * 描述某类附件的上传规则（大小上限、允许格式、分片大小等），
     * 供前端做前置校验并决定走分片还是单次直传，避免前后端各写一份硬编码规则。
     */
    Map<String, Object> describePolicy(MediaKind kind);
}
