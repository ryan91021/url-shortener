package com.urlshort.shortener.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean("clickEventExecutor")
    public Executor clickEventExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);                 // 常駐 2 條
        ex.setMaxPoolSize(4);                  // 尖峰最多 4 條
        ex.setQueueCapacity(100);              // ★ 有界佇列＝背壓來源（不是預設的 Integer.MAX_VALUE）
        ex.setThreadNamePrefix("click-async-");// ★ 驗證 async 生效就靠這個執行緒名
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy()); // 滿載→呼叫線程自己跑（背壓）
        ex.setWaitForTasksToCompleteOnShutdown(true); // 關機前把佇列跑完（別讓已收的 click 掉）
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // void 回傳的 @Async 方法若拋例外，預設會被「默默吞掉」；這裡兜底記下來
        return (throwable, method, params) ->
                log.error("async 方法 {} 拋出未捕捉例外", method.getName(), throwable);
    }
}