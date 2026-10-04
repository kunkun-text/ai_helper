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

        // 【B2 · 2026-10-05】只取非空字段参与更新；空字段不再覆盖原值（旧全量 UPDATE 会把
        // 没填的字段写空、甚至触发非空约束）
        String name = trimToNull(editUserInfo.getName());
        String phoneNumber = trimToNull(editUserInfo.getPhoneNumber());
        String newUserNumber = trimToNull(editUserInfo.getUserNumber());
        String email = trimToNull(editUserInfo.getEmail());

        if (name == null && phoneNumber == null && newUserNumber == null && email == null) {
            return Result.error("没有可更新内容");
        }

        // 【B2】长度/格式校验
        if (name != null && name.length() > 50) {
            return Result.error("姓名长度不能超过 50 字");
        }
        if (newUserNumber != null && !newUserNumber.matches("\\w{2,32}")) {
            return Result.error("学号/工号格式不正确（2~32位字母、数字或下划线）");
        }
        if (phoneNumber != null && !phoneNumber.matches("1\\d{10}")) {
            return Result.error("手机号格式不正确");
        }
        if (email != null && (email.length() > 100 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
            return Result.error("邮箱格式不正确");
        }

        // 【B2】唯一性冲突预检：改学号/邮箱前先确认没被其他账号占用，返回友好提示
        if (newUserNumber != null
                && editUserInfoMapper.countByUserNumberExcludingId(newUserNumber, loginUser.getId().trim()) > 0) {
            return Result.error("该学号/工号已被使用");
        }
        if (email != null
                && editUserInfoMapper.countByEmailExcludingId(email, loginUser.getId().trim()) > 0) {
            return Result.error("该邮箱已被使用");
        }

        EditUserInfoDto partial = new EditUserInfoDto();
        partial.setId(loginUser.getId().trim());
        partial.setName(name);
        partial.setPhoneNumber(phoneNumber);
        partial.setUserNumber(newUserNumber);
        partial.setEmail(email);
        editUserInfoMapper.editUserInfo(partial);
        return Result.success();
    }

    /** 空白串归一为 null（不参与更新） */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
