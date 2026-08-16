package com.tam.notification.redis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class RedisClusterSlotTest {
    @Test
    void shouldMatchRedisKnownSlots() {
        // 这两个结果可以使用 redis-cli CLUSTER KEYSLOT 再次核对。
        assertEquals(12_182, RedisClusterSlot.slot("foo"));
        assertEquals(5_061, RedisClusterSlot.slot("bar"));
    }

    @Test
    void shouldUseFirstNonEmptyHashTag() {
        assertEquals(
                RedisClusterSlot.slot("user1000"),
                RedisClusterSlot.slot("{user1000}.following")
        );
        assertEquals(
                RedisClusterSlot.slot("{user1000}.following"),
                RedisClusterSlot.slot("{user1000}.followers")
        );
    }

    @Test
    void emptyFirstBracesShouldHashWholeKey() {
        assertEquals(
                "foo{}{bar}",
                RedisClusterSlot.hashKey("foo{}{bar}")
        );
        assertNotEquals(
                RedisClusterSlot.slot("bar"),
                RedisClusterSlot.slot("foo{}{bar}")
        );
    }

    @Test
    void shouldGroupKeysByActualSlot() {
        List<String> keys = List.of(
                "shortlink:{A}:redirect",
                "shortlink:{A}:negative",
                "shortlink:{B}:redirect"
        );

        Map<Integer, List<String>> groups = RedisClusterSlot.groupBySlot(keys);

        assertEquals(2, groups.size());
        assertEquals(3, groups.values().stream().mapToInt(List::size).sum());

        groups.forEach((slot, group) -> group.forEach(
                key -> assertEquals(slot, RedisClusterSlot.slot(key))
        ));
    }
}
