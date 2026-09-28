package com.ai_helper.ai_helper.Config;

import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：异步线程池、登录校验拦截器、附件静态资源映射。
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /**
     * 需要登录才能访问的路径。
     *
     * <p>采用「按需保护」而非全站保护：答辩链路（/api/chat 等）不在其中，避免影响面过大。</p>
     *
     * <p><b>2026-09-28（N19 越权修复）</b>：补入 {@code /teacher/**} 与 {@code /editUserInfo}。
     * 此前这两块不在保护范围内，学生只要登录就能删改课题、改任意他人资料。
     * 教师端所需角色由 Controller 上的 {@code @RequireRole} 声明（见 AuthInterceptor），
     * 本数组只负责「是否要求先登录」。</p>
     */
    private static final String[] PROTECTED_PATHS = {
            "/api/video/**",
            "/api/report/**",
            "/student/**",
            "/teacher/**",
            "/editUserInfo"
    };

    private final AuthInterceptor authInterceptor;
    private final AppProperties appProperties;
    private final FileStorageService fileStorageService;

    @Bean("taskExecutor")
    public AsyncTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mvc-task-");
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(taskExecutor());
    }

    /**
     * 注册登录校验拦截器。
     *
     * <p>注意：{@link AuthInterceptor} 之前虽然写好了、也标了 {@code @Component}，
     * 但从未在这里注册过，导致上述接口实际处于「不登录也能访问」的状态；
     * 也因为登录态缺失，学生接口只能信任前端传的 userNumber，存在越权读取他人数据的风险。</p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(PROTECTED_PATHS);
    }

    /**
     * 把附件存储根目录映射为静态资源，供 &lt;video&gt; 播放与文件查看/下载。
     *
     * <p>用 ResourceHandler 而不是自己写 Controller 的原因：它原生支持 HTTP Range 请求，
     * 视频才能边下边播、拖动进度条。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String urlPrefix = appProperties.getStorage().getUrlPrefix();
        // 目录 URI 由 FileStorageService 提供：存储根目录可能是运行时自动选定的磁盘，
        // 照配置自己拼会拼出错误路径。
        String location = fileStorageService.rootUri();
        log.info("附件静态资源映射 - {}/** -> {}", urlPrefix, location);
        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
