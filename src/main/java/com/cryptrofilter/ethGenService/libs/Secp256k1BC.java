package com.cryptrofilter.ethGenService.libs;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;

import java.math.BigInteger;

public class Secp256k1BC {
    public static final X9ECParameters SECP = CustomNamedCurves.getByName("secp256k1");
    public static final ECCurve CURVE = SECP.getCurve();
    public static final ECPoint G = SECP.getG();
    public static final BigInteger N = SECP.getN();

    // Быстрое умножение на базовую точку с предвычислениями (BC)
    public static ECPoint mulBase(BigInteger k) {
        return new FixedPointCombMultiplier().multiply(G, k).normalize();
    }

    public static ECPoint add(ECPoint a, ECPoint b) {
        return a.add(b); // без normalize тут!
    }
}
