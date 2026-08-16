package com.tam.notification.redis;


import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * redis cluster 槽位计算工具
 * 算法必须与redis保持一致：
 * 1、提取第一对非空大括号中的 hash tag
 * 2、对 UTF-8 字节执行 CRC16-XMODEM
 * 3、保留低14唯，得到0-16383
 */
public final class RedisClusterSlot {

    public static final int SLOT_COUNT = 16_384;
    private static final int SLOT_MASK = SLOT_COUNT - 1;

    private RedisClusterSlot() {
    }


    /**
     * 计算一个真实的 redis key 所属的槽位
     *
     * @param key
     * @return
     */
    public static int slot(String key) {
        Objects.requireNonNull(key, "redis key不能为空");

        byte[] bytes = hashKey(key).getBytes(StandardCharsets.UTF_8);
        return crc16(bytes) & SLOT_MASK;
    }

    /**
     * 按槽位分组，并保留输入顺讯
     * 每一组可以安全执行 MEGET/MSET等同槽位多key命令
     *
     * @param keys
     * @return
     */
    public static Map<Integer, List<String>> groupBySlot(
            Collection<String> keys
    ) {
        Objects.requireNonNull(keys, "keys不能为空");

        return keys.stream()
                .map(key -> Objects.requireNonNull(key, "key不能为空"))
                .collect(Collectors.groupingBy(
                        RedisClusterSlot::slot,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }


    /**
     * redis只识别第一对大括号。
     * 第一对括号为空时必须按照完整key哈希，不能继续寻找后面的括号
     *
     * @param key
     * @return
     */
    static String hashKey(String key) {
        int open = key.indexOf('{' );
        if (open < 0) {
            return key;
        }
        int close = key.indexOf('}', open + 1);
        if (close > open + 1) {
            return key.substring(open + 1, close); // 截取括号内的内容
        }

        return key;
    }

    /**
     * crc16-XMODEM，多项式0x1021初始值0
     *
     * @param bytes
     * @return
     */
    private static int crc16(byte[] bytes) {
        int crc = 0;

        for (byte value : bytes) {
            crc ^= (value & 0xFF) << 8;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }

                crc &= 0xFFFF;
            }
        }

        return crc;
    }
}
