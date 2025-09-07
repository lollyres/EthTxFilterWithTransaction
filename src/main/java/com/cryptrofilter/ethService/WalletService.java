package com.cryptrofilter.ethService;

import com.cryptrofilter.dto.Wallet;
import com.cryptrofilter.ethGenService.VanityService;
import com.cryptrofilter.ethGenService.records.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private static final SendTransactionService sendTransactionService = new SendTransactionService();
    private static final String BANK_PRIVATE_KEY = "0x43d445f3327606850e3507468849a4a4cc8e5809364759a44e172820ebb2c3a6";

    public void processWallet(String prefix, String suffix, String toAddress, String fromAddress) {
        try {
            Wallet wallet = this.generateWallet(prefix, suffix);

            if (wallet != null) {
                logger.info(wallet.getAddress(), wallet.getPrivateKey());

                if (sendTransactionService.sendFundsOnNewWallet(wallet.getAddress(), BANK_PRIVATE_KEY, "0.00000021")) {
                    logger.info("Send two tx");
                    sendTransactionService.sendFundsOnNewWallet(toAddress, wallet.getPrivateKey(), "0.0000000021");
                }

//                TxSender.enqueue(() -> {
//                    sendTransactionService.sendFundsOnNewWallet(wallet, "0.000000021");
//                });

            } else {
                logger.warn("Wallet is empty");
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    private Wallet generateWallet(String prefix, String suffix) {
        try (VanityService svc = new VanityService(8)) {
            byte[] p = com.cryptrofilter.ethGenService.utils.Utils.hexStringToBytes(prefix);
            byte[] s = com.cryptrofilter.ethGenService.utils.Utils.hexStringToBytes(suffix);
            VanityService.Job job = svc.request(3, p, s);

            while (!job.isDone()) {
                Optional<Hit> hitOptional = job.poll(Duration.ofSeconds(5));
                if (hitOptional.isPresent()) {
                    Hit hit = hitOptional.get();
                    logger.info("HIT: 0x{} priv={}", hit.checksum(), hit.priv().toString(16));

//                    byte[] enc = Encryptor.encryptAesGcm(
//                            com.cryptrofilter.ethGenService.utils.Utils.to32(hit.priv().toByteArray()),
//                            "strong-pass".toCharArray()
//                    ); ЗАШИФРОВАННЫЙ ПРИВАТНЫЙ КЛЮЧ

                    return new Wallet(hit.checksum(), hit.priv().toString(16));
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
