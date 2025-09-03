package com.cryptrofilter.ethService.utils;

import java.math.BigDecimal;
import java.math.BigInteger;

public class Utils {
    public static String json(String s) {
        return s.replaceAll("\\s+", " ");
    }

    public static BigInteger hexToBigInt(String hex) {
        if (hex == null || hex.isEmpty() || "0x".equals(hex)) return BigInteger.ZERO;
        return new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
    }

    public static String formatWeiToEth(String weiHex) {
        BigInteger wei = hexToBigInt(weiHex);
        BigDecimal eth = new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"));
        return eth.stripTrailingZeros().toPlainString();
    }
}
