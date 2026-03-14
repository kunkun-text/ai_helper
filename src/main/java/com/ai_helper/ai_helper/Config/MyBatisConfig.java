package com.ai_helper.ai_helper.Config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.ai_helper.ai_helper.mapper")
public class MyBatisConfig {
    // 其他配置...
}
   