package com.ai_helper.ai_helper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>【N71 · 2026-10-15】开启 {@code @Scheduled} 支持：分片上传的孤儿预分配文件
 * 定时清理（ChunkUploadServiceImpl#cleanupOrphanPreallocFiles，6 小时一轮）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class AiHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiHelperApplication.class, args);
    }

}


