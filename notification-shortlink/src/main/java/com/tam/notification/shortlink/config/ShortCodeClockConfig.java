package com.tam.notification.shortlink.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Snowflake的时间源配置
 * 生产使用UTC系统时钟，测试直接向生成器注入可控Clock，不需要修改操作系统时间
 */
@Configuration
public class ShortCodeClockConfig {

    @Bean("shortCodeClock")
    public Clock shortCodeClock() {
        return Clock.systemUTC();
    }
}
