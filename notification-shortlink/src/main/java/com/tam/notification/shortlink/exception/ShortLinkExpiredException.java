package com.tam.notification.shortlink.exception;

import com.tam.notification.common.exception.BusinessException;

public class ShortLinkExpiredException extends BusinessException {
    public ShortLinkExpiredException() {
        super(ShortLinkErrorCode.SHORT_LINK_EXPIRED);
    }
}
