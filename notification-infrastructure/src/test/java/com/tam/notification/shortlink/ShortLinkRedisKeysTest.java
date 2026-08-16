package com.tam.notification.shortlink;

import com.tam.notification.redis.RedisClusterSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShortLinkRedisKeysTest {
    @Test
    void sameShortCodeKeysShouldUseSameSlotAndStableFormat() {
        String code = "aZ8k2LmP";
        String redirect = ShortLinkRedisKeys.redirect(code);

        assertEquals("shortlink:{aZ8k2LmP}:redirect", redirect);
        assertEquals("shortlink:{aZ8k2LmP}:click:count",
                ShortLinkRedisKeys.clickCount(code));
        assertEquals(RedisClusterSlot.slot(redirect),
                RedisClusterSlot.slot(ShortLinkRedisKeys.negative(code)));
        assertEquals(RedisClusterSlot.slot(redirect),
                RedisClusterSlot.slot(ShortLinkRedisKeys.clickCount(code)));
    }

    @Test
    void allBloomV3KeysShouldUseSameSlot() {
        int expectedSlot = RedisClusterSlot.slot(ShortLinkRedisKeys.bloomReady());

        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSliceRegistry()));
        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSlice(1723809600L)));
        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSlice(1723831200L)));
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
        assertThrows(IllegalArgumentException.class,
                () -> ShortLinkRedisKeys.redirect("bad{code}"));
    }
}
