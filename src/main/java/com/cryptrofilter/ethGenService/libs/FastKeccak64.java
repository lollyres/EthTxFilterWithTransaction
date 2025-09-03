package com.cryptrofilter.ethGenService.libs;

/**
 * Keccak-256 (SHA3-256 без финального домена), специализировано на вход ровно 64 байта.
 * Без выделений, результат 32 байта пишется в out32.
 */
public final class FastKeccak64 {
    private FastKeccak64() {}

    // Константы раундов
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L };

    // Rho-офсеты
    private static final int[] R = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14 };

    // Pi перестановка
    private static final int[] PI = {
            0, 6,12,18,24,
            3, 9,10,16,22,
            1, 7,13,19,20,
            4, 5,11,17,23,
            2, 8,14,15,21 };

    private static long rol64(long x, int n) {
        return (x << n) | (x >>> (64 - n));
    }

    /**
     * Хеширует 64-байтовый буфер (x||y) в out32 (Keccak-256).
     * out32.length >= 32
     */
    public static void hash64(byte[] in64, byte[] out32) {
        // Состояние 5x5 lane (25 * 64 бит)
        long[] s = new long[25];

        // rate = 136, но у нас только 64 байта. Поглощаем половину блока.
        // Читка little-endian 8-байтовых слов.
        // in64[0..63] -> s[0..7]
        for (int i = 0; i < 8; i++) {
            int off = i << 3;
            long v =  ((long)in64[off] & 0xFF)
                    | (((long)in64[off+1] & 0xFF) << 8)
                    | (((long)in64[off+2] & 0xFF) << 16)
                    | (((long)in64[off+3] & 0xFF) << 24)
                    | (((long)in64[off+4] & 0xFF) << 32)
                    | (((long)in64[off+5] & 0xFF) << 40)
                    | (((long)in64[off+6] & 0xFF) << 48)
                    | (((long)in64[off+7] & 0xFF) << 56);
            s[i] ^= v;
        }

        // Паддинг в том же rate-блоке:
        // ставим 0x01 сразу после данных (байт 64), и 0x80 в последний байт rate-строки (байт 135)
        // Эти биты ложатся в lane s[8] (младшие 8 байт блока 64..71)
        s[8] ^= 0x0000000000000001L;     // бит домена
        // Последний байт rate (позиция 135) — это lane s[16] бит 63 (старший бит)
        s[16] ^= 0x8000000000000000L;    // финальный бит 1

        // 24 раунда Keccak-f[1600]
        keccakF1600(s);

        // Squeeze 32 байта из s (lane s[0..3], little-endian)
        // s[0], s[1], s[2], s[3] -> 32 байта
        int o = 0;
        for (int i = 0; i < 4; i++) {
            long v = s[i];
            out32[o++] = (byte)( v        & 0xFF);
            out32[o++] = (byte)((v >>> 8) & 0xFF);
            out32[o++] = (byte)((v >>>16) & 0xFF);
            out32[o++] = (byte)((v >>>24) & 0xFF);
            out32[o++] = (byte)((v >>>32) & 0xFF);
            out32[o++] = (byte)((v >>>40) & 0xFF);
            out32[o++] = (byte)((v >>>48) & 0xFF);
            out32[o++] = (byte)((v >>>56) & 0xFF);
        }
    }

    private static void keccakF1600(long[] s) {
        for (int rnd = 0; rnd < 24; rnd++) {
            long c0 = s[0]^s[5]^s[10]^s[15]^s[20];
            long c1 = s[1]^s[6]^s[11]^s[16]^s[21];
            long c2 = s[2]^s[7]^s[12]^s[17]^s[22];
            long c3 = s[3]^s[8]^s[13]^s[18]^s[23];
            long c4 = s[4]^s[9]^s[14]^s[19]^s[24];

            long d0 = rol64(c1,1) ^ c4;
            long d1 = rol64(c2,1) ^ c0;
            long d2 = rol64(c3,1) ^ c1;
            long d3 = rol64(c4,1) ^ c2;
            long d4 = rol64(c0,1) ^ c3;

            for (int i=0;i<25;i+=5) {
                s[i  ] ^= d0;
                s[i+1] ^= d1;
                s[i+2] ^= d2;
                s[i+3] ^= d3;
                s[i+4] ^= d4;
            }

            // Rho+Pi
            long[] b = new long[25];
            for (int i=0;i<25;i++) {
                b[PI[i]] = rol64(s[i], R[i]);
            }

            // Chi
            for (int i=0;i<25;i+=5) {
                long b0=b[i  ], b1=b[i+1], b2=b[i+2], b3=b[i+3], b4=b[i+4];
                s[i  ] = b0 ^ ((~b1) & b2);
                s[i+1] = b1 ^ ((~b2) & b3);
                s[i+2] = b2 ^ ((~b3) & b4);
                s[i+3] = b3 ^ ((~b4) & b0);
                s[i+4] = b4 ^ ((~b0) & b1);
            }

            // Iota
            s[0] ^= RC[rnd];
        }
    }
}