package com.mail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Web 邮箱客户端后端启动类。
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
public class MailClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailClientApplication.class, args);
    }

    /**
     * 周期任务开关：{@code --mail.app.scheduling-enabled=false} 让进程正常提供服务但不跑调度。
     * 验证或排查单个账户时用得上，可确保不会有后台收信打到真实邮箱上。
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "mail.app.scheduling-enabled", matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
