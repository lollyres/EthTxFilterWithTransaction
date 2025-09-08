package com.cryptrofilter.ethService;

import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;

public class TransactionPoolingService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionPoolingService.class);
    private static final Web3j web3Http = Web3j.build(new HttpService("https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5"));

    public void getNewBlock() {
        try {
            EthBlockNumber block = web3Http.ethBlockNumber().send();
            logger.info(String.valueOf(block));
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.getMessage());
        }
    }
}
