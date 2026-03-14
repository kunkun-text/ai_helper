package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.stereotype.Service;

@Service
public interface editUserInfoService {

    Result editUserInfo(EditUserInfoDto editUserInfo);
}
