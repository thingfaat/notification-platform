package com.tam.notification.shortlink;

import com.tam.notification.redis.RedisClusterSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShortLinkRedisKeysTest {
    @Test
    void sameShortCodeKeysShouldUseSameSlot() {
        String shortCode = "aZ8k2LmP";

        int redirectSlot = RedisClusterSlot.slot(
                ShortLinkRedisKeys.redirect(shortCode)
        );

        assertEquals(
                redirectSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.negative(shortCode))
        );
        assertEquals(
                redirectSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.clickCount(shortCode))
        );
    }

    @Test
    void bloomKeysShouldUseSameGlobalSlot() {
        assertEquals(
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomBitmap()),
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomReady())
        );
    }

    @Test
    void differentShortCodesShouldRemainDistributable() {
        assertNotEquals(
                RedisClusterSlot.slot(ShortLinkRedisKeys.redirect("aZ8k2LmP")),
                RedisClusterSlot.slot(ShortLinkRedisKeys.redirect("Xy98Mn76"))
        );
    }

    @Test
    void bracesMustNotEnterHashTag() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShortLinkRedisKeys.redirect("bad{code}")
        );
    }
}
