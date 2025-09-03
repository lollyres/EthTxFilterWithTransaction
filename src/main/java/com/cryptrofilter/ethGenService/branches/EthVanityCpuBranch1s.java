package com.cryptrofilter.ethGenService.branches;

import com.cryptrofilter.ethGenService.libs.Keccak;
import com.cryptrofilter.ethGenService.utils.Utils;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static com.cryptrofilter.ethGenService.libs.Secp256k1BC.*;

public class EthVanityCpuBranch1s {
    public static void main(String[] args) throws Exception {
        // === Параметры бенча ===
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        long seconds = 5; // длительность замера
        if (args.length >= 1) {
            threads = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            seconds = Long.parseLong(args[1]);
        }

        System.out.printf("Benchmark: threads=%d, duration=%ds%n", threads, seconds);

        // Инициализация стартовых значений
        SecureRandom rng = new SecureRandom();
        BigInteger k0 = new BigInteger(256, rng).mod(N);
        BigInteger stride = BigInteger.valueOf(threads);
        ECPoint Gstride = mulBase(stride); // общее для всех потоков

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong totalAttempts = new AtomicLong(0);

        // дедлайн бенча
        final long endNanos = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; ++t) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    BigInteger k = k0.add(stride.multiply(BigInteger.valueOf(tid))).mod(N);
                    ECPoint Q = mulBase(k); // одно дорогое умножение на поток
                    long local = 0;

                    while (System.nanoTime() < endNanos) {
                        // 1) паблик → (x||y) 64 байта
                        ECPoint P = Q.normalize();
                        byte[] x = Utils.to32(P.getAffineXCoord().toBigInteger().toByteArray());
                        byte[] y = Utils.to32(P.getAffineYCoord().toBigInteger().toByteArray());
                        byte[] pub = Utils.xyConcat32(x, y);

                        // 2) keccak(pub) → addr20 (результат не используем)
                        byte[] h = Keccak.keccak256(pub);
                        // берём 20 байт (но ни с чем не сравниваем)
                        // byte[] addr20 = Arrays.copyOfRange(h, 12, 32);

                        // 3) следующий кандидат: Q += G_stride; k += stride
                        Q = add(Q, Gstride);
                        k = k.add(stride);

                        local++;
                    }
                    totalAttempts.addAndGet(local);
                } catch (Throwable th) {
                    th.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        pool.shutdownNow();

        double tps = totalAttempts.get() / (double) seconds;
        System.out.printf("Done: %,d addresses in %d s — throughput ≈ %, .0f addr/s%n",
                totalAttempts.get(), seconds, tps);
    }
}