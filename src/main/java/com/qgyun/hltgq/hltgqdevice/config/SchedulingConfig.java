package com.qgyun.hltgq.hltgqdevice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务调度线程池配置：@Scheduled 任务并发执行，互不阻塞。
 * <p>Spring Boot 默认单线程调度器下，长任务（30分钟节拍视频轮巡，单轮最长约10分钟）
 * 会阻塞同刻触发的整点任务（站点同步/事件订阅刷新），故扩为3线程池。
 * 各任务内部已有互斥锁（轮巡 running / 站点同步 syncRunning），并发执行安全。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 轮巡 / 站点同步 / 事件订阅刷新 三个定时任务各占一线程
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("hltgq-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
