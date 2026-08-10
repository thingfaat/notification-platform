package com.tam.notification.shortlink.exception;

import com.tam.notification.common.exception.BusinessException;

public class ShortLinkNotFoundException extends BusinessException {
    public ShortLinkNotFoundException() {
        super(ShortLinkErrorCode.SHORT_LINK_NOT_FOUND);
    }
}
