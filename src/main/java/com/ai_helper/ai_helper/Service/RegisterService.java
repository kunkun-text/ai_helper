package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.stereotype.Service;

@Service
public interface RegisterService {

    Result register(UserDto userDto);

    Result login(UserDto userDto);
}
