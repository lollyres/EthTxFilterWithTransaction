package com.cryptrofilter.ethGenService.utils;

import org.bouncycastle.crypto.digests.KeccakDigest;

import java.util.HexFormat;

public class Utils {
    public static byte[] xyConcat32(byte[] x32, byte[] y32) {
        byte[] out = new byte[64];
        System.arraycopy(x32, 0, out, 0, 32);
        System.arraycopy(y32, 0, out, 32, 32);
        return out;
    }

    // Нормализует big-endian число в ровно 32 байта (обрезает/паддит слева нулями)
    public static byte[] to32(byte[] bigEndian) {
        byte[] out = new byte[32];
        int srcPos = Math.max(0, bigEndian.length - 32);
        int len    = Math.min(32, bigEndian.length);
        System.arraycopy(bigEndian, srcPos, out, 32 - len, len);
        return out;
    }

    // EIP-55 checksum
    public static String toEthChecksum(byte[] addr20) {
        String lower = HexFormat.of().formatHex(addr20);
        byte[] dh = new byte[32];

        KeccakDigest d = new KeccakDigest(256);
        byte[] lowerBytes = lower.getBytes();
        d.update(lowerBytes, 0, lowerBytes.length);
        d.doFinal(dh, 0);

        String hs = HexFormat.of().formatHex(dh);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); ++i) {
            char c = lower.charAt(i);
            int nibble = Character.digit(hs.charAt(i), 16);
            sb.append(Character.isLetter(c) && nibble >= 8 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    // 1) HEX -> bytes с авто-паддингом ведущим нулём для нечётной длины
    public static byte[] hexStringToBytes(String hex) {
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        // Разрешаем нечётную длину — добиваем ведущим '0'
        if ((clean.length() & 1) != 0) clean = "0" + clean;

        int len = clean.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        }
        return out;
    }

    // 2) Вписать big-endian число в ровно 32 байта (в уже выделенный буфер)
    public static void to32Into(byte[] bigEndian, byte[] dst32) {
        // dst32.length == 32
        int srcPos = Math.max(0, bigEndian.length - 32);
        int len    = Math.min(32, bigEndian.length);
        // обнулить dst (на случай переиспользования)
        for (int i = 0; i < 32; i++) dst32[i] = 0;
        System.arraycopy(bigEndian, srcPos, dst32, 32 - len, len);
    }

    // 3) Keccak в уже выделенный буфер (снизит аллокации)
    public static void keccak256Into(byte[] in, byte[] out32) {
        org.bouncycastle.crypto.digests.KeccakDigest d =
                new org.bouncycastle.crypto.digests.KeccakDigest(256);
        d.update(in, 0, in.length);
        d.doFinal(out32, 0);
    }
}
