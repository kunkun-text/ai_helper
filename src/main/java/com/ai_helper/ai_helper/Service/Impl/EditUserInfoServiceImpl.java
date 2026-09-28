package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.editUserInfoService;
import com.ai_helper.ai_helper.mapper.EditUserInfoMapper;
import com.ai_helper.ai_helper.mapper.RegisterMapper;
import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 个人资料修改。
 *
 * <p>【N19 · 2026-09-28】此前直接把请求体里的 id 作为 WHERE 条件更新，
 * 任意登录用户都能改他人姓名 / 学号 / 邮箱。现在要求「请求体的 id 必须等于登录账号的 user_id」。</p>
 */
@Slf4j
@Service
public class EditUserInfoServiceImpl implements editUserInfoService {

    @Resource
    private EditUserInfoMapper editUserInfoMapper;

    /** 按学号 / 工号取登录用户的 user_id，复用已有查询，不新增 SQL */
    @Resource
    private RegisterMapper registerMapper;

    @Override
    public Result editUserInfo(EditUserInfoDto editUserInfo, String loginUserNumber) {
        if (editUserInfo == null || UploadUtils.isBlank(loginUserNumber)) {
            return Result.error("登录状态已失效，请重新登录");
        }

        UserDto loginUser = registerMapper.selectByUserNumber(loginUserNumber.trim());
        if (loginUser == null || UploadUtils.isBlank(loginUser.getId())) {
            return Result.error("登录状态已失效，请重新登录");
        }

        // 归属校验：只能改自己
        if (UploadUtils.isBlank(editUserInfo.getId())
                || !loginUser.getId().trim().equals(editUserInfo.getId().trim())) {
            log.warn("拒绝越权修改资料 - 登录用户: {}, 请求目标 id: {}", loginUserNumber, editUserInfo.getId());
            return Result.error("只能修改自己的资料");
        }

        editUserInfoMapper.editUserInfo(editUserInfo);
        return Result.success();
    }
}
