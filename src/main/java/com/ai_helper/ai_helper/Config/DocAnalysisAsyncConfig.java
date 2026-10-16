package com.ai_helper.ai_helper.Config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 报告文档评析的专用线程池（2026-10-16 · 报告五维评分第一期）。
 *
 * <p><b>为什么不复用 {@code taskExecutor}：</b>那个池是给异步落库/语音等服务的小任务用的
 * （core=2）；文档评析是**长任务**（抽文本 + 多次模型调用，单次预算 240 秒），
 * 一旦并发跑两个就会同时抢 Ollama 显存——本项目历史上正是显存/内存打爆留下过
 * {@code hs_err_pid} 崩溃日志。所以这里单开一个 **core=max=1** 的池，物理上保证串行。</p>
 *
 * <p>队列只给 4，且用 {@link ThreadPoolExecutor.AbortPolicy} 直接拒绝而不是无限排队：
 * 排到第 5 个的时候，前面几个已经在占用 CPU 好几分钟了，继续排队只会让教师看到一个
 * 永远不动的进度条；不如立刻告诉他"稍后重试"。</p>
 */
@Slf4j
@Configuration
public class DocAnalysisAsyncConfig {

    @Bean("docAnalysisExecutor")
    public Executor docAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("doc-analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("报告评析线程池已就绪 - core=1, max=1, queue=4（低功耗机串行，避免抢显存）");
        return executor;
    }
}
