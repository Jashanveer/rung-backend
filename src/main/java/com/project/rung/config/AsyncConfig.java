package com.project.rung.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for {@code @Async} methods (AI mentor reply, welcome
 * regeneration, etc.).
 *
 * Default Spring behaviour with bare {@code @EnableAsync} and no
 * {@code TaskExecutor} bean is to fall back to {@link
 * org.springframework.core.task.SimpleAsyncTaskExecutor} which spawns a
 * fresh thread per task and never reuses or caps them. Combined with the
 * AI mentor reply path, an attacker who can submit messages faster than
 * Anthropic responds can OOM the JVM with stack-allocated threads each
 * holding an open HTTP connection.
 *
 * Sizing logic:
 *   - corePoolSize 4: handles steady-state mentor traffic without warming
 *   - maxPoolSize 16: tolerates a burst (e.g. scheduler tick + a few user
 *     replies arriving simultaneously) without queuing latency
 *   - queueCapacity 100: smooths transient bursts; CallerRunsPolicy
 *     beyond that backpressures the producer (the @TransactionalEventListener
 *     in AiMentorReplyListener) instead of dropping work silently
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Override
    @Bean(name = "taskExecutor")
    public TaskExecutor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("async-");
        // CallerRunsPolicy backpressures the producer (typically the AI
        // event listener thread) when both the queue and the max pool are
        // saturated — better than silently dropping AI replies.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        exec.initialize();
        log.info("Async executor: core={} max={} queue={}",
                exec.getCorePoolSize(), exec.getMaxPoolSize(), 100);
        return exec;
    }
}
