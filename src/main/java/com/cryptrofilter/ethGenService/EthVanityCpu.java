package com.cryptrofilter.ethGenService;

import com.cryptrofilter.ethGenService.utils.Utils;
import com.cryptrofilter.ethGenService.libs.Encryptor;
import com.cryptrofilter.ethGenService.libs.FastKeccak64;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.cryptrofilter.ethGenService.libs.Secp256k1BC.*;

public class EthVanityCpu {
    public static void main(String[] args) throws Exception {
        // === Настройки ===
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        long reportEvery = 200_000; // каждые N попыток/поток печатать метрику

        // Префикс/суффикс как HEX-строки — "DEAD" и "BEEF"
        byte[] prefix = Utils.hexStringToBytes("DEAD"); // 0xDE,0xAD
        byte[] suffix = Utils.hexStringToBytes("BEEF"); // 0xBE,0xEF

        if (prefix.length > 20 || suffix.length > 20) {
            throw new IllegalArgumentException("Prefix/suffix must be <= 20 bytes");
        }

        // === Инициализация ===
        SecureRandom rng = new SecureRandom();
        BigInteger k0 = new BigInteger(256, rng).mod(N);           // стартовый скаляр
        BigInteger stride = BigInteger.valueOf(threads);           // шаг по скалярам = число потоков
        ECPoint Gstride = mulBase(stride);                         // общее для всех потоков

        System.out.println("threads=" + threads);
        System.out.println("start k0=" + k0.toString(16));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch stop = new CountDownLatch(1);
        ConcurrentLinkedQueue<Result> hits = new ConcurrentLinkedQueue<>();

        Instant t0 = Instant.now();

        for (int t = 0; t < threads; ++t) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    BigInteger k = k0.add(stride.multiply(BigInteger.valueOf(tid))).mod(N);
                    ECPoint Q = mulBase(k); // одно дорогое умножение на поток
                    long ctr = 0;

                    // Предвыделенные буферы на поток
                    byte[] pub = new byte[64];
                    byte[] h   = new byte[32];

                    while (stop.getCount() > 0) {
                        // 1) Паблик → 64 байта (x||y).
                        // ВАЖНО: Secp256k1BC.add(...) у нас уже возвращает normalize(),
                        // поэтому тут можно без повторного normalize():
                        byte[] enc65 = Q.getEncoded(false); // 65 байт: 0x04||x||y
                        System.arraycopy(enc65, 1, pub, 0, 64);

                        // 2) keccak(pub) → h (32 байта), адрес = h[12..31] (20 байт)
//                        Keccak.keccak256Into(pub, h);
                        FastKeccak64.hash64(pub, h);

                        // 3) быстрый матчинг: префикс + суффикс на addr20 = h[12..31]
                        if (matchAddr20PrefixSuffix(h, 12, prefix, suffix)) {
                            byte[] addr20 = new byte[20];
                            System.arraycopy(h, 12, addr20, 0, 20);

                            String addr = Utils.toEthChecksum(addr20);
                            hits.add(new Result(k, addr20));
                            System.out.println("HIT: 0x" + addr + "  priv(hex)=" + k.toString(16));
                            stop.countDown();
                            break;
                        }

                        // 4) Следующий кандидат: Q += G_stride; k += stride
                        Q = add(Q, Gstride);  // add() в Secp256k1BC уже нормализует
                        k = k.add(stride);

                        if ((++ctr % reportEvery) == 0) {
                            Duration d = Duration.between(t0, Instant.now());
                            double tps = ctr / Math.max(1e-3, d.toSeconds());
                            System.out.printf("[tid=%d] tried=%d ~%.1f/s%n", tid, ctr, tps);
                        }
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            });
        }

        // Ожидание первого хита
        stop.await();
        pool.shutdownNow();

        // Сохранение первого хита (пример шифрования)
        Result r = hits.peek();
        if (r != null) {
            byte[] enc = Encryptor.encryptAesGcm(r.privAs32(), "b0a3b2619b684512639663a3da63788b781c8ce28f35da8f7bb50ac1d621f8c7".toCharArray());
        }
    }

    private static boolean matchAddr20PrefixSuffix(byte[] h, int off, byte[] prefix, byte[] suffix) {

        if (prefix != null && prefix.length > 0) {
            for (int i = 0; i < prefix.length; i++) {
                if (h[off + i] != prefix[i]) return false;
            }
        }

        if (suffix != null && suffix.length > 0) {
            int start = off + 20 - suffix.length;
            for (int i = 0; i < suffix.length; i++) {
                if (h[start + i] != suffix[i]) return false;
            }
        }
        return true;
    }

    static class Result {
        final BigInteger k;
        final byte[] addr20;

        Result(BigInteger k, byte[] addr20) {
            this.k = k;
            this.addr20 = addr20;
        }

        byte[] privAs32() { return Utils.to32(k.toByteArray()); }
    }
}