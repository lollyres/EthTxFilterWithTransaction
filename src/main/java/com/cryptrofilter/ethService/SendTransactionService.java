package com.cryptrofilter.ethService;

import com.cryptrofilter.dto.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
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

//    private static final Credentials BANK_CRED = Credentials.create("0x43d445f3327606850e3507468849a4a4cc8e5809364759a44e172820ebb2c3a6");
//    private static final String BANK_ADDR = BANK_CRED.getAddress();
    private static final long CHAIN_ID = 1L;

    // минимальный gasPrice, чтобы не плодить "мертвые" pending с 0.x gwei
    private static final BigInteger MIN_GWEI = BigInteger.valueOf(5_000_000_000L); // 5 gwei
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);

//    public boolean sendFundsOnNewWallet(Wallet wallet, String amountEthStr) {
//        if (wallet == null || wallet.getAddress() == null) {
//            log.error("wallet is null");
//            return false;
//        }
//        final String to = wallet.getAddress();
//
//        try {
//            BigInteger valueWei = Convert.toWei(new BigDecimal(amountEthStr), Convert.Unit.ETHER).toBigIntegerExact();
//
//            // стартовый gasPrice: max(eth_gasPrice * 2, 5 gwei)
//            BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
//            BigInteger gasPrice = gp.multiply(BigInteger.valueOf(2));
//            if (gasPrice.compareTo(MIN_GWEI) < 0) gasPrice = MIN_GWEI;
//
//            Boolean txHash = sendLegacyWithExplicitNonce(to, valueWei, gasPrice);
//            if (sendLegacyWithExplicitNonce(to, valueWei, gasPrice)) return true;
//        } catch (Exception e) {
//            log.error("sendFundsOnNewWallet failed", e);
//            throw new RuntimeException(e);
//        }
//        return false;
//    }

    public boolean sendFundsOnNewWallet(String to, String privateKey, String amountEthStr) {
//        if (wallet == null || wallet.getAddress() == null) {
//            log.error("wallet is null");
//            return false;
//        }

        try {
            BigInteger valueWei = Convert.toWei(new BigDecimal(amountEthStr), Convert.Unit.ETHER).toBigIntegerExact();

            // стартовый gasPrice: max(eth_gasPrice * 2, 5 gwei)
            BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
            BigInteger gasPrice = gp.multiply(BigInteger.valueOf(2));
            if (gasPrice.compareTo(MIN_GWEI) < 0) gasPrice = MIN_GWEI;

//            Boolean txHash = sendLegacyWithExplicitNonce(to, valueWei, gasPrice);
            if (sendLegacyWithExplicitNonce(to, privateKey, valueWei, gasPrice)) return true;
        } catch (Exception e) {
            log.error("sendFundsOnNewWallet failed", e);
            throw new RuntimeException(e);
        }
        return false;
    }

    private boolean sendLegacyWithExplicitNonce(String to,
                                               String privateKey,
                                               BigInteger valueWei,
                                               BigInteger gasPrice) throws Exception {
        int attempt = 0;

        while (true) {
            Credentials CRED = Credentials.create(privateKey);

            // 1) берём nonce у ноды (PENDING)
            EthGetTransactionCount cnt = web3j.ethGetTransactionCount(
                    CRED.getAddress(), DefaultBlockParameterName.PENDING).send();
            BigInteger nonce = cnt.getTransactionCount();

            // 2) собираем тип-0 вручную с ЯВНЫМ nonce
            RawTransaction tx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, GAS_LIMIT, to, valueWei);


            byte[] signed = TransactionEncoder.signMessage(tx, CHAIN_ID, CRED);
            String rawHex = Numeric.toHexString(signed);

            EthSendTransaction resp = web3j.ethSendRawTransaction(rawHex).send();
            if (!resp.hasError()) {
                String txHash = resp.getTransactionHash();
                TransactionReceipt txInfo = this.waitForReceipt(txHash);
                log.info("Tx Status: {}", txInfo.getStatus());
                log.info("sent tx={} → {}", txHash, to);
                if (txInfo.getStatus().equals("0x1")) {
                    return true;
                }
                return false;
            }

            String msg = resp.getError().getMessage();
            String low = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
            log.warn("RPC error (attempt {}): {}", attempt, msg);

            if (attempt++ >= 8) throw new RuntimeException("give up: " + msg);

            // a) "nonce too low"/"already known" → инкрементируем локально и пробуем снова
            if (low.contains("nonce too low") || low.contains("known transaction")
                    || low.contains("already imported") || low.contains("same hash was already imported")
                    || low.contains("replacement transaction underpriced and limit exceeds allowance")) {
                // просто небольшой backoff — и попробуем снова (возьмём новый pending nonce у ноды)
                sleep(250L * attempt);
                continue;
            }

            // b) "replacement underpriced" / "fee too low" → поднимаем gasPrice и пробуем снова
            if (low.contains("underpriced") || low.contains("fee too low") || low.contains("max fee per gas less than block base fee")) {
                gasPrice = gasPrice.add(gasPrice.divide(BigInteger.valueOf(10)).max(MIN_GWEI)); // +10% минимум
                sleep(200L * attempt);
                continue;
            }

            // c) "nonce too high" (редко) → просто подождём и повторим (нода догонит pending)
            if (low.contains("nonce too high")) {
                sleep(400L * attempt);
                continue;
            }

            // прочее — валим наружу
            throw new RuntimeException(msg);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }


    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        var processor = new PollingTransactionReceiptProcessor(
                web3j,
                1000,   // задержка между запросами (мс)
                40      // количество попыток (примерно 40 сек ожидания)
        );
        return processor.waitForTransactionReceipt(txHash);
    }
}