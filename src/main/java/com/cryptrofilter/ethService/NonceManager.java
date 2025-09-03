package com.cryptrofilter.ethService;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class NonceManager {
    private static final NonceManager INSTANCE = new NonceManager();
    private final ConcurrentHashMap<String, AtomicLong> lastUsed = new ConcurrentHashMap<>();

    private NonceManager() {}
    public static NonceManager getInstance() { return INSTANCE; }

    /** Резервируем следующий nonce = max(pending, latest, lastUsed+1) */
    public synchronized BigInteger next(Web3j web3j, String from) throws IOException {
        BigInteger pending = getCount(web3j, from, DefaultBlockParameterName.PENDING);
        BigInteger latest  = getCount(web3j, from, DefaultBlockParameterName.LATEST);
        long base = BigInteger.ZERO.max(pending).max(latest).longValue();

        AtomicLong a = lastUsed.computeIfAbsent(from, k -> new AtomicLong(base - 1));
        long next = Math.max(base, a.get() + 1);
        a.set(next); // резерв
        return BigInteger.valueOf(next);
    }

    /** Посмотреть текущий lastUsed без инкремента (для исправлений). */
    public synchronized BigInteger peekLastUsed(String from) {
        AtomicLong a = lastUsed.get(from);
        return a == null ? BigInteger.valueOf(-1) : BigInteger.valueOf(a.get());
    }

    /** Установить точно lastUsed = x (например pending-1). */
    public synchronized void setLastUsed(String from, BigInteger used) {
        lastUsed.put(from, new AtomicLong(used.longValue()));
    }

    /** «Отменить» последний next() — декремент на 1, если > -1. */
    public synchronized void unreserve(String from) {
        AtomicLong a = lastUsed.get(from);
        if (a != null) a.decrementAndGet();
    }

    /** Синхронизироваться на pending-1. */
    public synchronized void resyncToPendingMinus1(Web3j web3j, String from) throws IOException {
        BigInteger pending = getCount(web3j, from, DefaultBlockParameterName.PENDING);
        setLastUsed(from, pending.subtract(BigInteger.ONE));
    }

    private static BigInteger getCount(Web3j web3j, String from, DefaultBlockParameterName tag) throws IOException {
        var resp = web3j.ethGetTransactionCount(from, tag).send();
        if (resp.hasError() || resp.getTransactionCount() == null) {
            String msg = resp.getError() != null ? resp.getError().getMessage() : "null result";
            throw new IOException("eth_getTransactionCount(" + tag + ") failed: " + msg);
        }
        return resp.getTransactionCount();
    }
}