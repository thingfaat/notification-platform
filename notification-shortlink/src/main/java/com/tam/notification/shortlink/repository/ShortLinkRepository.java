package com.tam.notification.shortlink.repository;

import com.tam.notification.shortlink.domain.ShortLink;

public interface ShortLinkRepository {

    ShortLink save(ShortLink shortLink);
}
