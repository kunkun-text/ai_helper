package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.editUserInfoService;
import com.ai_helper.ai_helper.mapper.EditUserInfoMapper;
import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EditUserInfoServiceImpl implements editUserInfoService {

    @Autowired
    private EditUserInfoMapper editUserInfoMapper;

    @Override
    public Result editUserInfo(EditUserInfoDto editUserInfo) {
        editUserInfoMapper.editUserInfo(editUserInfo);
        return Result.success();
    }
}
