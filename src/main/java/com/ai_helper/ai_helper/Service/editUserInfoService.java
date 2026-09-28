package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.stereotype.Service;

@Service
public interface editUserInfoService {

    /**
     * 修改个人资料。
     *
     * @param editUserInfo    待修改字段（其中 id 必须是登录用户自己的 user_id）
     * @param loginUserNumber 当前登录学号 / 工号（取自登录态，不接受前端传入）
     */
    Result editUserInfo(EditUserInfoDto editUserInfo, String loginUserNumber);
}
