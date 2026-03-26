package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.VideoUploadService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VideoUploadServiceImpl implements VideoUploadService {
    
    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;
    
    @Override
    public void saveVideoUrl(String url, String userId) {
        log.info("保存视频 URL 到数据库，userId: {}, url: {}", userId, url);
        defenseRecordsMapper.insertVideoUrl(userId, url);
    }
}
