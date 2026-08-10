package com.tam.notification.shortlink.exception;

import com.tam.notification.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShortLinkErrorCode implements ErrorCode {
    SHORT_LINK_NOT_FOUND(
            "SHORTLINK_404_001",
            "短链不存在"
    ),

    SHORT_LINK_EXPIRED(
            "SHORTLINK_410_001",
            "短链已过期"
    );

    private final String code;

    private final String message;

}
