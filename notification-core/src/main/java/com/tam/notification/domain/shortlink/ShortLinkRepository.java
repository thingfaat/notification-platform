package com.tam.notification.domain.shortlink;

import java.util.Optional;

public interface ShortLinkRepository {

    ShortLink save(ShortLink shortLink);

    Optional<ShortLink> findById(Long id);
}
