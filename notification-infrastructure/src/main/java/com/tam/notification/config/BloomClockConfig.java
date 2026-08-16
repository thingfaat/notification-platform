package com.tam.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BloomClockConfig {

    /**
     * 生产统一使用UTC：测试可以直接向对象注入 MutableClock
     *
     * @return
     */
    @Bean
    public Clock bloomClock() {
        return Clock.systemUTC();
    }
}
