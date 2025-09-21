package com.cryptrofilter.ethService;

import com.cryptrofilter.dto.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private static final SendTransactionService sendTransactionService = new SendTransactionService();
    private static final String BANK_PRIVATE_KEY = "e4b8472fd4ff6ca995db7810db0f07b8439fcd547b29b992cf6d221d6c9a6053";

    public void processWallet(String prefix, String suffix, String toAddress, String fromAddress) {
        try {
            logger.info("Address example: {}", toAddress);
            Wallet wallet = this.generateWallet(prefix, suffix);

            if (wallet != null) {

                logger.info(wallet.getAddress());
                logger.info(wallet.getPrivateKey());
//                sendTransactionService.sendFast(BANK_PRIVATE_KEY, wallet.getAddress());

//                if (sendTransactionService.sendFundsOnNewWallet(wallet.getAddress(), BANK_PRIVATE_KEY, "0.000021")) {
//                    logger.info("Send second tx");
//                    Thread.sleep(Duration.ofSeconds(10000));
//                    sendTransactionService.sendZeroTxUltraCheap(wallet.getPrivateKey(), fromAddress);
//                }
            } else {
                logger.warn("Wallet is empty");
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    private Wallet generateWallet(String prefix, String suffix) {
        try {
            String binaryPath = "/root/EthTxFilterWithTransaction/eth-vanity-cuda/eth-vanity-address";

            ProcessBuilder builder = new ProcessBuilder(
                    binaryPath,
                    "--device", "0",
                    "--prefix", prefix,
                    "--suffix", suffix,
                    "--work-scale", "12"
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String address = null;
            String privateKey = null;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Private Key:") && line.contains("Address:")) {
                    String[] parts = line.split("Private Key:")[1].split("Address:");
                    if (parts.length == 2) {
                        privateKey = parts[0].trim();
                        address = parts[1].trim();
                    }
                    break;
                }
            }

            process.destroy();
            process.waitFor();

            if (address != null) {
                return new Wallet(address, privateKey);
            } else {
                logger.warn("Wallet not found in output");
                return null;
            }

        } catch (Exception e) {
            logger.error("Failed to generate vanity wallet: {}", e.getMessage(), e);
            return null;
        }
    }
}
