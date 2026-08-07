package com.tam.notification.common.web;

import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.common.trace.TraceContext;

import java.time.Instant;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {
    /**
     *  成功响应
     * @param data
     * @return
     * @param <T>
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                CommonErrorCode.SUCCESS.getCode(),
                CommonErrorCode.SUCCESS.getMessage(),
                data,
                TraceContext.getTraceId(),
                Instant.now()
        );
    }

    /**
     * 失败响应
     * @param code
     * @param message
     * @return
     * @param <T>
     */
    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(
                code,
                message,
                null,
                TraceContext.getTraceId(),
                Instant.now()
        );
    }
}
