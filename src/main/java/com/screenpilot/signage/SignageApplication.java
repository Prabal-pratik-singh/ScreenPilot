package com.screenpilot.signage;

import com.screenpilot.signage.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the ScreenPilot backend. {@code @SpringBootApplication} boots the whole
 * Spring context (component scanning, auto-configuration, embedded web server).
 * {@code @EnableScheduling} turns on {@code @Scheduled} background jobs (e.g. marking screens
 * offline), {@code @EnableAsync} allows {@code @Async} methods to run on a separate thread,
 * and {@code @EnableConfigurationProperties} binds the {@code app.*} settings from
 * application.yml into the type-safe {@link AppProperties} bean.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class SignageApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignageApplication.class, args);
    }
}
