package com.tam.notification.domain.template;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板渲染器
 */
public class SimpleTemplateRenderer implements TemplateRenderer {

    private final static Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 模板渲染
     * @param template
     * @param params
     * @return
     */
    @Override
    public String render(final String template, final Map<String, Object> params) {
        final var matcher = VARIABLE_PATTERN.matcher(template);

        final var result = new StringBuilder();
        while (matcher.find()) { // 匹配${}
            String variable = matcher.group(1);
            Object value = params.get(variable);

            if (value == null) {
                throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "缺少模板参数" + variable);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
