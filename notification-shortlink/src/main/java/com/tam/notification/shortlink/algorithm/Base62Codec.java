package com.tam.notification.shortlink.algorithm;

/**
 * 无损base62编解码器
 * 这里只做进制转换，不负责生成唯一ID，也绝不会为了固定长度对结果取模或者截断
 */
public final class Base62Codec {

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static final int RADIX = ALPHABET.length;

    private Base62Codec() {
    }

    /**
     * 把非负long无损编码为base62
     *
     * @param value
     * @return
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("base62只接受非负整数");
        }

        if (value == 0) {
            return "0";
        }

        /**
         * 正long的base62最长为11位，固定数组可避免循环扩容
         * java的long最大值为2的63次方-1，也就是9223372036854775807，base62每一位承载62种可能性，
         * 62的11次方大于9223372036854775807，所以11位足够了
         */
        char[] buffer = new char[11];
        int position = buffer.length;

        long remaining = value;
        // 对62进行取模，得到余数，作为当前位
        while (remaining > 0) {
            int digit = (int) (remaining % RADIX);
            buffer[--position] = ALPHABET[digit];
            remaining /= RADIX;
        }
        return new String(buffer, position, buffer.length - position);
    }

    /**
     * 解码用于证明编码是一一映射；生产跳转链路并不依赖反解
     *
     * @param value
     * @return
     */
    public static long decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("base62编码的字符串不能为空");
        }

        long result = 0l;

        for (int index = 0; index < value.length(); index++) {
            int digit = indexOf(value.charAt(index));
            if (digit < 0) {
                throw new IllegalArgumentException("base62编码的字符串包含非法字符");
            }

            // 使用精确算术，防止非法超长输入静默溢出
            // addExact、multiplyExact函数的作用：返回其参数的总和、乘积和，如果结果溢出了long类型，则抛出异常
            result = Math.addExact(
                    Math.multiplyExact(result, RADIX), // 编码的时候是除，解码的时候是乘
                    digit // 相当于是编码的时候模数
            );
        }

        return result;
    }

    /**
     * 查找字符在ALPHABET中的索引
     *
     * @param target
     * @return
     */
    private static int indexOf(char target) {
        for (int index = 0; index < ALPHABET.length; index++) {
            if (ALPHABET[index] == target) {
                return index;
            }
        }
        return -1;
    }
}
