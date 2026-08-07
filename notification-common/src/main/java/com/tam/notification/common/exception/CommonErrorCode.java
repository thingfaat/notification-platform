package com.tam.notification.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    SUCCESS("000000", "成功"),
    INVALID_PARAMETER("COMMON_400_001", "请求参数不合法"),
    BUSINESS_ERROR("COMMON_400_002", "业务处理失败"),
    INTERNAL_ERROR("COMMON_500_001", "系统内部异常");

    private final String code;
    private final String message;
}
