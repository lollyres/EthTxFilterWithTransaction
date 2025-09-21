package com.cryptrofilter.ethService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

public final class SendTransactionService {
    private static final Logger log = LoggerFactory.getLogger(SendTransactionService.class);

    private static final String URL = "https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5";
    private static final Web3j web3j = Web3j.build(new HttpService(URL));
    private static final long CHAIN_ID = 1L;

    private static final BigInteger MIN_GWEI = BigInteger.valueOf(5_000_000_000L); // 5 gwei
    private static final BigInteger ABS_MIN_GWEI = BigInteger.valueOf(1_000_000_000L); // 1 gwei
    private static final BigInteger ONE_GWEI = BigInteger.valueOf(1_000_000_000L);


    public boolean sendFundsOnNewWallet(String to, String privateKey, String amountEthStr) {
        try {
            final Credentials cred = Credentials.create(privateKey);
            final BigInteger valueWei = Convert.toWei(new BigDecimal(amountEthStr), Convert.Unit.ETHER).toBigIntegerExact();

            BigInteger gasPrice = cheapGasPrice();

            return sendLegacyWithExplicitNonce(to, cred, valueWei, gasPrice);
        } catch (Exception e) {
            log.error("sendFundsOnNewWallet failed", e);
            throw new RuntimeException(e);
        }
    }

    private boolean sendLegacyWithExplicitNonce(String to, Credentials cred, BigInteger valueWei, BigInteger gasPrice) throws Exception {
        int attempt = 0;

        while (true) {
            BigInteger nonce = web3j.ethGetTransactionCount(cred.getAddress(), DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();

            BigInteger gasLimit = estimateGasForTransfer(cred.getAddress(), to, valueWei, gasPrice, nonce);
            if (gasLimit.compareTo(BigInteger.valueOf(21_000)) < 0) {
                gasLimit = BigInteger.valueOf(21_000);
            }

            BigInteger balance = web3j.ethGetBalance(cred.getAddress(), DefaultBlockParameterName.PENDING).send().getBalance();
            BigInteger fee = gasPrice.multiply(gasLimit);
            if (balance.compareTo(valueWei.add(fee)) < 0) {
                log.error("Insufficient funds: balance={}, needed={}", balance, valueWei.add(fee));
                return false;
            }

            RawTransaction tx = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, to, valueWei);
            byte[] signed = TransactionEncoder.signMessage(tx, CHAIN_ID, cred);
            EthSendTransaction resp = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();

            if (!resp.hasError()) {
                String txHash = resp.getTransactionHash();
                TransactionReceipt txInfo = this.waitForReceipt(txHash);
                log.info("Tx mined. hash={}, status={}, block={}", txHash, txInfo.getStatus(), txInfo.getBlockNumber());
                return "0x1".equalsIgnoreCase(txInfo.getStatus());
            }

            String msg = String.valueOf(resp.getError().getMessage());
            String low = msg.toLowerCase(Locale.ROOT);
            log.warn("RPC error (attempt {}): {}", attempt, msg);

            if (attempt++ >= 8) throw new RuntimeException("give up: " + msg);

            if (low.contains("gas required exceeds allowance") || low.contains("intrinsic gas too low")) {
                gasPrice = gasPrice.max(MIN_GWEI);
                sleep(100L * attempt);
                continue;
            }

            if (low.contains("nonce too low") || low.contains("known transaction")
                    || low.contains("already imported") || low.contains("same hash was already imported")
                    || low.contains("replacement transaction underpriced and limit exceeds allowance")) {
                sleep(250L * attempt);
                continue;
            }

            if (low.contains("underpriced") || low.contains("fee too low") || low.contains("max fee per gas less than block base fee")) {
                // пересчитаем от текущего baseFee, а не умножать слепо
                try {
                    BigInteger fresh = cheapGasPrice(); // baseFee(pending) + 0.2 gwei
                    // возьми максимум из текущего и "свежего", затем +10% буфер
                    gasPrice = fresh.max(gasPrice).multiply(BigInteger.valueOf(11)).divide(BigInteger.TEN);
                } catch (Exception ignore) {
                    // если не смогли получить baseFee — мягко поднимем на +10%
                    gasPrice = gasPrice.add(gasPrice.divide(BigInteger.TEN)).max(ABS_MIN_GWEI);
                }
                sleep(200L * attempt);
                continue;
            }

            if (low.contains("nonce too high")) {
                sleep(400L * attempt);
                continue;
            }

            throw new RuntimeException(msg);
        }
    }

    private BigInteger estimateGasForTransfer(String from, String to, BigInteger valueWei, BigInteger gasPrice, BigInteger nonce) {
        try {
            var call = org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(
                    from,
                    nonce,          // nonce влияет на некоторые провайдеры — передаем тот же
                    gasPrice,       // можно null, но часть RPC любит видеть gasPrice
                    null,           // gas = null → узел сам оценит
                    to,
                    valueWei
            );
            var est = web3j.ethEstimateGas(call).send().getAmountUsed();
            if (est == null || est.signum() == 0) est = BigInteger.valueOf(21_000);
            // +10% запас
            return est.multiply(BigInteger.valueOf(11)).divide(BigInteger.TEN);
        } catch (Exception ex) {
            // fallback: EOA → 21k; неизвестно → 60k
            log.debug("eth_estimateGas failed, fallback: {}", ex.toString());
            return BigInteger.valueOf(60_000);
        }
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        var processor = new PollingTransactionReceiptProcessor(
                web3j,
                1000,   // задержка между запросами (мс)
                40      // количество попыток (примерно 40 сек ожидания)
        );
        return processor.waitForTransactionReceipt(txHash);
    }

    public BigInteger cheapGasPrice() throws Exception {
        BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
        BigInteger gasPrice = gp.divide(BigInteger.valueOf(2))
                .max(BigInteger.valueOf(1_000_000_000L));

        return gasPrice.max(ABS_MIN_GWEI);
    }

    public boolean sendZeroTxUltraCheap(String fromPriv, String toAddr) {
        try {
            Credentials cred = Credentials.create(fromPriv);

            // 1) ждём дешёвую сеть (напр., пока <= 5 gwei) до 2 минут
            BigInteger currentGp = waitForCheapGas(120_000L, BigInteger.valueOf(5)); // 5 gwei cap

            // 2) стартуем вообще с "очень дешево": /8 от эталона, но >= 1 gwei
            BigInteger gasPrice = currentGp.divide(BigInteger.valueOf(8)).max(ONE_GWEI);

            BigInteger valueWei = BigInteger.ZERO;           // «нулевая» транза
            BigInteger gasLimit = BigInteger.valueOf(21_000);

            // 4) отправляем через твой метод (он ждёт receipt и обрабатывает ошибки)
            return sendLegacyWithExplicitNonce(toAddr, cred, valueWei, gasPrice);
        } catch (Exception e) {
            log.error("sendZeroTxUltraCheap failed", e);
            return false;
        }
    }

    private BigInteger ultraCheapGasPrice(int divisor) throws Exception {
        // divisor: 4, 8, 16 — насколько «режем» цену
        BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
        BigInteger cheap = gp.max(ONE_GWEI).divide(BigInteger.valueOf(Math.max(1, divisor)));
        return cheap.max(ONE_GWEI); // не меньше 1 gwei
    }

    private BigInteger waitForCheapGas(long maxWaitMillis, BigInteger capGwei) throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        BigInteger cap = capGwei.multiply(ONE_GWEI); // cap в gwei → wei
        while (true) {
            BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
            if (gp.compareTo(cap) <= 0) return gp;
            if (System.currentTimeMillis() > deadline) return gp; // не дождались — шлём как есть
            Thread.sleep(1_000L);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

}