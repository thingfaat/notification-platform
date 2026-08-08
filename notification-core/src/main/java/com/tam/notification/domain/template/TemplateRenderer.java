package com.tam.notification.domain.template;

import java.util.Map;

/**
 * 模板渲染
 */
public interface TemplateRenderer {
    String render(String template, Map<String, Object> params);
}
