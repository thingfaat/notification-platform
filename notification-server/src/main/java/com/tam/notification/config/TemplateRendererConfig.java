package com.tam.notification.config;

import com.tam.notification.domain.template.SimpleTemplateRenderer;
import com.tam.notification.domain.template.TemplateRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemplateRendererConfig {

    @Bean
    public TemplateRenderer templateRenderer() {
        return new SimpleTemplateRenderer();
    }
}
