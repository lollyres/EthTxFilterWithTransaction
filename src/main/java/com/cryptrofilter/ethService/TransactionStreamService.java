package com.cryptrofilter.ethService;

import io.reactivex.exceptions.UndeliverableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.utils.Convert;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.concurrent.Semaphore;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class TransactionStreamService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionStreamService.class);

    //    private static final String ALCHEMY_WS = "wss://eth-mainnet.g.alchemy.com/v2/I8j55L6wsHVgTT1s43_Si";
    private static final String INFURA_WS = "wss://mainnet.infura.io/ws/v3/6096c2acec854ec99f8b6945bda33ce5";

    private static final WebSocketService ws = new WebSocketService(INFURA_WS, true);
    private static final BigDecimal minAmount = BigDecimal.valueOf(0.1); // Min Amount in ETH
    private static final Web3j web3j = Web3j.build(ws);

    private static final Semaphore TX_PERMITS = new Semaphore(200);

    private final WalletService walletService = new WalletService();

    public void connect() {
        try {
            ws.connect();
            web3j.newHeadsNotifications()
                    .subscribe(head -> {
                        String hash = head.getParams().getResult().getHash();
                        web3j.ethGetBlockByHash(hash, true)
                                .sendAsync()
                                .thenAccept(block -> {
                                    if (block == null || block.getBlock() == null) {
                                        logger.warn("Received null block from stream, skipping...");
                                        return;
                                    }

                                    BigInteger blockNum = block.getBlock().getNumber();

                                    logger.info("Block: {} Transactions Count: {}", blockNum, block.getBlock().getTransactions().size());

                                    block.getBlock().getTransactions().forEach(txResult -> {
                                        EthBlock.TransactionObject tx = (EthBlock.TransactionObject) txResult.get();

                                        if (TX_PERMITS.tryAcquire()) {
                                            Thread.ofVirtual().start(() -> {
                                                try {
                                                    this.processTransaction(tx, blockNum);
                                                } finally {
                                                    TX_PERMITS.release();
                                                }
                                            });
                                        }

                                    });
                                });
                    }, throwable -> {
                        if (throwable instanceof UndeliverableException) {
                            Throwable cause = throwable.getCause();
                            logger.warn("Undeliverable exception caught: {}",
                                    cause != null ? cause.getMessage() : throwable.getMessage());
                        } else {
                            logger.error("RxJava global error: {}", throwable.getMessage(), throwable);
                        }
                    });
        } catch (ConnectException e) {
            throw new RuntimeException(e);
        }
    }

    private void processTransaction(EthBlock.TransactionObject tx, BigInteger blockNum) {
        String to = tx.getTo();
        String from = tx.getFrom();
        if (to == null) return;

        if (!"0x".equals(tx.getInput())) return;

        try {
            EthGetCode code = web3j.ethGetCode(to, DefaultBlockParameter.valueOf(blockNum)).send();
            if (!"0x".equals(code.getCode())) return;

            BigDecimal ethValue = Convert.fromWei(new BigDecimal(tx.getValue()), Convert.Unit.ETHER);
            if (this.filterTransactionByAmount(ethValue)) {
                String clean = to.substring(2);
                String prefix = "0x" + clean.substring(0, 2);
                String suffix = "0x" + clean.substring(clean.length() - 2);

                walletService.processWallet(prefix, suffix, to, from);

//                if (TX_PERMITS.tryAcquire()) {
//                    Thread.ofVirtual().start(() -> {
//                        try {
//                            walletService.processWallet(prefix, suffix, to, from);
//                        } finally {
//                            TX_PERMITS.release();
//                        }
//                    });
//                }
            }
        } catch (IOException e) {
            logger.error("eth_getCode failed: {}", e.getMessage());
        }
    }

    private boolean filterTransactionByAmount(BigDecimal ethValue) {
        return ethValue.compareTo(minAmount) >= 0;
    }
}
