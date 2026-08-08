package com.tam.notification.domain.enums;

import lombok.Getter;

/**
 * 启用状态
 */
@Getter
public enum EnabledStatus {
    DISABLED(0),
    ENABLED(1);

    private final int code;

    EnabledStatus(int code) {
        this.code = code;
    }

}
