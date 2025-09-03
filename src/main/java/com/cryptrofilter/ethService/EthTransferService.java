package com.cryptrofilter.ethService;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

public final class EthTransferService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EthTransferService.class);
    private static final String URL = "https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5";

    // Таймауты на HTTP, чтобы не виснуть навсегда
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build();

    // Один Web3j на процесс
    private static final Web3j web3j = Web3j.build(new HttpService(URL, HTTP, false));
    private static final NonceManager NONCES = NonceManager.getInstance();

    /** Простой перевод ETH (legacy tx). Возвращает tx hash или кидает исключение. */
    public String sendEth(String privateKeyHex, String to, BigDecimal amountEth) throws Exception {
        Credentials cred = Credentials.create(strip0x(privateKeyHex));
        String from = cred.getAddress();

        BigInteger gasPrice = safeGasPrice().multiply(BigInteger.valueOf(2));
        BigInteger gasLimit = BigInteger.valueOf(21_000);
        BigInteger valueWei = Convert.toWei(amountEth, Convert.Unit.ETHER).toBigIntegerExact();

        int attempts = 0;
        while (true) {
            attempts++;
            BigInteger nonce = NONCES.next(web3j, from);

            RawTransaction raw = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, to, valueWei);
            byte[] signed = TransactionEncoder.signMessage(raw, 1L, cred);
            var send = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();

            if (!send.hasError()) {
                log.info("tx sent ok: nonce={}, hash={}", nonce, send.getTransactionHash());
                return send.getTransactionHash();
            }

            String err = send.getError() != null ? send.getError().getMessage() : "unknown";
            String low = err.toLowerCase();

            // ===== разруливание =====
            if (low.contains("nonce too low")) {
                // наш nonce уже использован узлом → двигаем локальный указатель к pending-1
                NONCES.resyncToPendingMinus1(web3j, from);
                log.warn("nonce too low; resynced to pending-1 and retry");
            } else if (low.contains("invalid nonce")) {
                // узел считает наш nonce невалидным (часто слишком высокий).
                // Пробуем маленькое окно начиная с on-chain pending.
                BigInteger pending = web3j.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
                boolean fixed = false;
                for (int i = 0; i < 4 && !fixed; i++) {
                    BigInteger tryNonce = pending.add(BigInteger.valueOf(i));
                    NONCES.setLastUsed(from, tryNonce.subtract(BigInteger.ONE));
                    RawTransaction rawTry = RawTransaction.createEtherTransaction(tryNonce, gasPrice, gasLimit, to, valueWei);
                    byte[] signedTry = TransactionEncoder.signMessage(rawTry, 1L, cred);
                    var sendTry = web3j.ethSendRawTransaction(Numeric.toHexString(signedTry)).send();
                    if (!sendTry.hasError()) {
                        log.info("tx sent after nonce scan: nonce={}, hash={}", tryNonce, sendTry.getTransactionHash());
                        return sendTry.getTransactionHash();
                    }
                    String e2 = sendTry.getError() != null ? sendTry.getError().getMessage() : "unknown";
                    if (e2.toLowerCase().contains("replacement underpriced")) {
                        gasPrice = gasPrice.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20%
                    }
                    // если опять invalid/low — цикл попробует следующий tryNonce
                }
                log.warn("invalid nonce persists; will retry outer loop");
            } else if (low.contains("replacement transaction underpriced") || low.contains("already known")) {
                gasPrice = gasPrice.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20%
                log.warn("{}; bump gasPrice to {} and retry same nonce next loop", err, gasPrice);
                // не двигаем lastUsed — тот же nonce переиспользуем
                NONCES.setLastUsed(from, NONCES.next(web3j, from).subtract(BigInteger.ONE)); // фиксим автоинкремент
            } else if (low.contains("too many requests") || low.contains("rate limit")) {
                Thread.sleep(400);
                log.warn("rate limited; backing off");
                // откатывать nonce не нужно — мы его не приняли узлом
                NONCES.setLastUsed(from, NONCES.next(web3j, from).subtract(BigInteger.ONE));
            } else {
                throw new RuntimeException("RPC error: " + err);
            }

            if (attempts >= 10) throw new RuntimeException("send failed after retries, last: " + err);
            Thread.sleep(150);
        }
    }

    private static BigInteger safeGasPrice() throws Exception {
        EthGasPrice resp = web3j.ethGasPrice().send();
        if (resp.hasError() || resp.getGasPrice() == null)
            throw new RuntimeException("eth_gasPrice failed: " + (resp.getError() != null ? resp.getError().getMessage() : "null"));
        return resp.getGasPrice();
    }

    private static String strip0x(String s) { return s != null && s.startsWith("0x") ? s.substring(2) : s; }

    @Override public void close() { web3j.shutdown(); }
}