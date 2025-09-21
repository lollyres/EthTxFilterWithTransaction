package com.cryptrofilter.ethService;

import com.cryptrofilter.dto.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private static final SendTransactionService sendTransactionService = new SendTransactionService();
    private static final String BANK_PRIVATE_KEY = "0x43d445f3327606850e3507468849a4a4cc8e5809364759a44e172820ebb2c3a6";

    public void processWallet(String prefix, String suffix, String toAddress, String fromAddress) {
        try {
            logger.info("Address example: {}", toAddress);
            Wallet wallet = this.generateWallet(prefix, suffix);

            if (wallet != null) {

                if (sendTransactionService.sendFundsOnNewWallet(wallet.getAddress(), BANK_PRIVATE_KEY, "0.000021")) {
                    logger.info("Send second tx");
                    Thread.sleep(Duration.ofSeconds(10));
                    sendTransactionService.sendZeroTxUltraCheap(wallet.getPrivateKey(), fromAddress);
                }
            } else {
                logger.warn("Wallet is empty");
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    private Wallet generateWallet(String prefix, String suffix) {
        return null;
    }
}
