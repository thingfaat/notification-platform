package com.tam.notification.domain.message;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.domain.template.SimpleTemplateRenderer;
import com.tam.notification.domain.template.TemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SimpleTemplateRendererTest {
    private final TemplateRenderer renderer = new SimpleTemplateRenderer();

    @Test
    void shouldRenderTemplate() {
        String result = renderer.render("您好${name}，订单${orderNo}", Map.of("name", "张三", "orderNo", "12345678"));
        assertEquals("您好张三，订单12345678", result);
    }

    @Test
    void shouldRejectMissingVariable() {
        assertThrows(BusinessException.class, () -> renderer.render("您好${name}", Map.of()));
    }
}
