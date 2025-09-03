package com.cryptrofilter.ethGenService.libs;

import org.bouncycastle.crypto.digests.KeccakDigest;

public class Keccak {
    public static byte[] keccak256(byte[] in) {
        KeccakDigest d = new KeccakDigest(256);
        d.update(in, 0, in.length);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    public static void keccak256Into(byte[] in, byte[] out32) {
        KeccakDigest d = new KeccakDigest(256);
        d.update(in, 0, in.length);
        d.doFinal(out32, 0);
    }
}
