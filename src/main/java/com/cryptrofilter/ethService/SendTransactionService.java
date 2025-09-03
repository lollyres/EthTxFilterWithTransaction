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
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

public final class SendTransactionService {
    private static final Logger log = LoggerFactory.getLogger(SendTransactionService.class);

    private static final String URL = "https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5";
    private static final Web3j web3j = Web3j.build(new HttpService(URL));

    private static final Credentials BANK_CRED = Credentials.create("0x43d445f3327606850e3507468849a4a4cc8e5809364759a44e172820ebb2c3a6");
    private static final String BANK_ADDR = BANK_CRED.getAddress();
    private static final long CHAIN_ID = 1L;

    // минимальный gasPrice, чтобы не плодить "мертвые" pending с 0.x gwei
    private static final BigInteger MIN_GWEI = BigInteger.valueOf(5_000_000_000L); // 5 gwei
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);

    public void sendFundsOnNewWallet(Wallet wallet, String fromIgnored, String pkIgnored, String amountEthStr) {
        if (wallet == null || wallet.getAddress() == null) {
            log.error("wallet is null");
            return;
        }
        final String to = wallet.getAddress();

        try {
            BigInteger valueWei = Convert.toWei(new BigDecimal(amountEthStr), Convert.Unit.ETHER).toBigIntegerExact();

            // стартовый gasPrice: max(eth_gasPrice * 2, 5 gwei)
            BigInteger gp = web3j.ethGasPrice().send().getGasPrice();
            BigInteger gasPrice = gp.multiply(BigInteger.valueOf(2));
            if (gasPrice.compareTo(MIN_GWEI) < 0) gasPrice = MIN_GWEI;

            String txHash = sendLegacyWithExplicitNonce(to, valueWei, gasPrice, 8);
            log.info("sent tx={} → {}", txHash, to);
        } catch (Exception e) {
            log.error("sendFundsOnNewWallet failed", e);
            throw new RuntimeException(e);
        }
    }

    private String sendLegacyWithExplicitNonce(String to,
                                               BigInteger valueWei,
                                               BigInteger gasPrice,
                                               int maxRetries) throws Exception {
        int attempt = 0;

        while (true) {
            // 1) берём nonce у ноды (PENDING)
            EthGetTransactionCount cnt = web3j.ethGetTransactionCount(
                    BANK_ADDR, DefaultBlockParameterName.PENDING).send();
            BigInteger nonce = cnt.getTransactionCount();

            // 2) собираем тип-0 вручную с ЯВНЫМ nonce
            RawTransaction tx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, GAS_LIMIT, to, valueWei);

            byte[] signed = TransactionEncoder.signMessage(tx, CHAIN_ID, BANK_CRED);
            String rawHex = Numeric.toHexString(signed);

            // 3) шлём
            EthSendTransaction resp = web3j.ethSendRawTransaction(rawHex).send();
            if (!resp.hasError()) return resp.getTransactionHash();

            // 4) разбор ошибок и ретраи
            String msg = resp.getError().getMessage();
            String low = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
            log.warn("RPC error (attempt {}): {}", attempt, msg);

            if (attempt++ >= maxRetries) throw new RuntimeException("give up: " + msg);

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
}