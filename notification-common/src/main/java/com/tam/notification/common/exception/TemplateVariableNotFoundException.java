package com.tam.notification.common.exception;

public class TemplateVariableNotFoundException extends BusinessException {
    public TemplateVariableNotFoundException(final ErrorCode errorCode) {
        super(errorCode);
    }

    public TemplateVariableNotFoundException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }
}
