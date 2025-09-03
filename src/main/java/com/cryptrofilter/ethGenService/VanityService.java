package com.cryptrofilter.ethGenService;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.cryptrofilter.ethGenService.libs.Keccak;
import com.cryptrofilter.ethGenService.records.Hit;
import com.cryptrofilter.ethGenService.utils.Utils;
import org.bouncycastle.math.ec.ECPoint;

import static com.cryptrofilter.ethGenService.libs.Secp256k1BC.*;
import static java.lang.Math.min;

public class VanityService implements AutoCloseable {

    public static final class Job {
        final byte[] prefix;
        final byte[] suffix;
        final AtomicInteger remaining;
        final LinkedBlockingQueue<Hit> out = new LinkedBlockingQueue<>();
        final AtomicBoolean done = new AtomicBoolean(false);

        Job(byte[] prefix, byte[] suffix, int need) {
            this.prefix = prefix; this.suffix = suffix;
            this.remaining = new AtomicInteger(need);
        }
        boolean tryDeliver(Hit h) {
            if (done.get()) return false;
            out.offer(h);
            if (remaining.decrementAndGet() <= 0) {
                done.set(true);
                return true;
            }
            return false;
        }
        public Hit take() throws InterruptedException { return out.take(); }
        public Optional<Hit> poll(Duration timeout) throws InterruptedException {
            Hit h = out.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(h);
        }
        public boolean isDone() { return done.get(); }
    }

    // ===== Сервис =====
    private final int threads;
    private final ExecutorService pool;
    private final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
    private final CountDownLatch stop = new CountDownLatch(1);

    public VanityService(int threads) {
        this.threads = threads;
        this.pool = Executors.newFixedThreadPool(threads);
        startWorkers();
    }

    public Job request(int count, byte[] prefix, byte[] suffix) {
        Job j = new Job(prefix.clone(), suffix.clone(), count);
        jobs.add(j);
        return j;
    }

    public void cancel(Job j) { j.done.set(true); jobs.remove(j); }

    // ===== Генерация кандидатов (как у тебя, но без вывода) =====
    private void startWorkers() {
        SecureRandom rng = new SecureRandom();
        BigInteger k0 = new BigInteger(256, rng).mod(N);
        BigInteger stride = BigInteger.valueOf(threads);
        ECPoint Gstride = mulBase(stride);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    BigInteger k = k0.add(stride.multiply(BigInteger.valueOf(tid))).mod(N);
                    ECPoint Q = mulBase(k);

                    byte[] pub = new byte[64];
                    byte[] h   = new byte[32];

                    while (stop.getCount() > 0) {
                        // 1) pub = x||y
                        byte[] enc65 = Q.getEncoded(false);
                        System.arraycopy(enc65, 1, pub, 0, 64);

                        // 2) keccak(pub) -> h
                        Keccak.keccak256Into(pub, h);

                        // 3) проверка активных заданий
                        if (!jobs.isEmpty()) {
                            // Снимем снапшот ссылкой, пройдёмся (очередь быстрая)
                            for (Job j : jobs) {
                                if (j.done.get()) continue;
                                if (match(h, 12, j.prefix, j.suffix)) {
                                    byte[] addr20 = Arrays.copyOfRange(h, 12, 32);
                                    String checksum = Utils.toEthChecksum(addr20);
                                    Hit hit = new Hit(addr20, checksum, k);
                                    boolean finished = j.tryDeliver(hit);
                                    if (finished) jobs.remove(j);
                                }
                            }
                        }

                        // 4) следующий кандидат
                        Q = add(Q, Gstride); // add() должно быть БЕЗ normalize
                        k = k.add(stride);
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            });
        }
    }

    private static boolean match(byte[] h, int off, byte[] prefix, byte[] suffix) {
        // prefix
        for (int i = 0; i < min(prefix.length, 20); i++) if (h[off + i] != prefix[i]) return false;
        // suffix
        for (int i = 0; i < min(suffix.length, 20); i++) {
            int start = off + 20 - suffix.length;
            if (h[start + i] != suffix[i]) return false;
        }
        return true;
    }

    @Override public void close() {
        stop.countDown();
        pool.shutdownNow();
    }
}