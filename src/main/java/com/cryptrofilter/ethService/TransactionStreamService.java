package com.cryptrofilter.ethService;

import io.reactivex.exceptions.UndeliverableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.utils.Convert;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class TransactionStreamService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionStreamService.class);

    //    private static final String ALCHEMY_WS = "wss://eth-mainnet.g.alchemy.com/v2/I8j55L6wsHVgTT1s43_Si";
    private static final String INFURA_WS = "wss://mainnet.infura.io/ws/v3/6096c2acec854ec99f8b6945bda33ce5";

    private static final BigDecimal minAmount = BigDecimal.valueOf(0.1); // Min Amount in ETH
//    private static final Web3j web3j = Web3j.build(ws);
    private static final WebSocketService ws = new WebSocketService(INFURA_WS, true);
    private static final Web3j web3Ws   = Web3j.build(ws);
    private static final Web3j web3Http = Web3j.build(new HttpService("https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5"));

    private static final Semaphore TX_PERMITS = new Semaphore(1);
    private final WalletService walletService = new WalletService();

    public void connect() {
        try {
            ws.connect();
            web3Ws.newHeadsNotifications()
                    .subscribe(head -> {
                        String hash = head.getParams().getResult().getHash();
                        web3Ws.ethGetBlockByHash(hash, true)
                                .sendAsync()
                                .thenAccept(block -> {
                                    if (block == null || block.getBlock() == null) {
                                        logger.warn("Received null block from stream, skipping...");
                                        return;
                                    }

                                    BigInteger blockNum = block.getBlock().getNumber();

                                    logger.info("Block: {} Transactions Count: {}", blockNum, block.getBlock().getTransactions().size());

                                    List<EthBlock.TransactionObject> txList = new ArrayList<>();

                                    block.getBlock().getTransactions().forEach(txResult -> {
                                        EthBlock.TransactionObject tx = (EthBlock.TransactionObject) txResult.get();
                                        txList.add(tx);
                                    });

                                    this.processTransaction(txList, blockNum);
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

    private void processTransaction(List<EthBlock.TransactionObject> txList, BigInteger blockNum) {
        txList.forEach(tx -> {
            String to = tx.getTo();
            String from = tx.getFrom();
            logger.info("Get TX");
            if (to == null) return;

            if (!"0x".equals(tx.getInput())) return;

            try {
                EthGetCode code = web3Http.ethGetCode(to, DefaultBlockParameter.valueOf(blockNum)).send();
                if (!"0x".equals(code.getCode())) return;

                BigDecimal ethValue = Convert.fromWei(new BigDecimal(tx.getValue()), Convert.Unit.ETHER);
                if (this.filterTransactionByAmount(ethValue)) {
                    String clean = to.substring(2);
                    String prefix = "0x" + clean.substring(0, 4);
                    String suffix = "0x" + clean.substring(clean.length() - 4);

                    logger.info("Tx Hash: {}", tx.getHash());
                    logger.info("Tx Value: {}", tx.getValue());
                    logger.info("Tx From Address : {}", tx.getFrom());
                    logger.info("Tx To Address : {}", tx.getTo());
                    logger.info("Generate new wallet");
                    walletService.processWallet(prefix, suffix, to, from);

                }
            } catch (IOException e) {
                logger.error("eth_getCode failed: {}", e.getMessage());
                this.connect();
            }
        });
    }

    private boolean filterTransactionByAmount(BigDecimal ethValue) {
        return ethValue.compareTo(minAmount) >= 0;
    }
}
