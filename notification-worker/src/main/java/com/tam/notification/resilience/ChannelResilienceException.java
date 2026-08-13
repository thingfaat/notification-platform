package com.tam.notification.resilience;

import lombok.Getter;

/**
 * 渠道调用异常
 */
@Getter
public class ChannelResilienceException extends RuntimeException {

    public enum Type {
        TIMEOUT, // 超时
        INTERRUPTED, // 中断
        ISOLATION_REJECTED, // 隔离拒绝
        CIRCUIT_OPEN // 熔断打开
    }

    private final Type type; // 错误类型

    private final String providerCode; // 渠道供应商编码

    private final boolean failoverAllowed; // 是否允许失败转移

    private ChannelResilienceException(
            Type type,
            String providerCode,
            boolean failoverAllowed,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.type = type;
        this.providerCode = providerCode;
        this.failoverAllowed = failoverAllowed;
    }

    /**
     * 超时
     * @param providerCode
     * @return
     */
    public static ChannelResilienceException timeout(
            String providerCode
    ) {
        return new ChannelResilienceException(
                Type.TIMEOUT,
                providerCode,
                false,
                "渠道调用超时，providerCode=" + providerCode,
                null
        );
    }

    /**
     * 中断
     * @param providerCode
     * @param cause
     * @return
     */
    public static ChannelResilienceException interrupted(
            String providerCode,
            Throwable cause
    ) {
        return new ChannelResilienceException(
                Type.INTERRUPTED,
                providerCode,
                false,
                "渠道调用被中断，providerCode=" + providerCode,
                cause
        );
    }

    /**
     * 隔离拒绝
     * @param providerCode
     * @return
     */
    public static ChannelResilienceException isolationRejected(
            String providerCode,
            Throwable cause
    ) {
        return new ChannelResilienceException(
                Type.ISOLATION_REJECTED,
                providerCode,
                false,
                "渠道调用被隔离拒绝，providerCode=" + providerCode,
                cause
        );
    }

    /**
     * 熔断打开
     * @param providerCode
     * @param failoverAllowed
     * @return
     */
    public static ChannelResilienceException circuitOpen(
            String providerCode,
            boolean failoverAllowed
    ) {
        return new ChannelResilienceException(
                Type.CIRCUIT_OPEN,
                providerCode,
                failoverAllowed,
                "渠道调用熔断打开，providerCode=" + providerCode,
                null
        );
    }

}
