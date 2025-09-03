// SpeedUpUtils.java
package com.cryptrofilter.ethService;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

public final class SpeedUpUtils {

    private SpeedUpUtils() {}

    /**
     * Заменяет (speed up) pending-транзакцию с данным nonce, отправляя новую legacy-транзу
     * с бОльшим gasPrice. По умолчанию делает "cancel": 0 ETH на свой же адрес.
     *
     * @param web3j      клиент
     * @param creds      приватный ключ отправителя (тот же, что у зависшей транзы)
     * @param chainId    chainId (mainnet = 1)
     * @param nonce      ТОТ ЖЕ nonce, что у зависшей транзы
     * @param gasPrice   новый gasPrice (в wei), должен быть заметно выше предыдущего
     * @return           хеш новой транзакции
     */
    public static String speedUpLegacyCancel(
            Web3j web3j,
            Credentials creds,
            long chainId,
            BigInteger nonce,
            BigInteger gasPrice
    ) throws Exception {
        // минимальный gasLimit для простой транзы без данных
        BigInteger gasLimit = BigInteger.valueOf(21_000);

        // self-send 0 ETH — "cancel" зависшей транзы
        String to = creds.getAddress();
        BigInteger value = BigInteger.ZERO;

        RawTransaction tx = RawTransaction.createEtherTransaction(
                nonce,         // тот же nonce
                gasPrice,      // БОЛЬШЕ, чем у зависшей!
                gasLimit,
                to,
                value
        );

        byte[] signed = TransactionEncoder.signMessage(tx, chainId, creds);
        String rawHex = Numeric.toHexString(signed);

        EthSendTransaction resp = web3j.ethSendRawTransaction(rawHex).send();
        if (resp.hasError()) throw new RuntimeException(resp.getError().getMessage());
        return resp.getTransactionHash();
    }

    /**
     * Вариант speed-up с отправкой на конкретный адрес и суммой (если хочешь заменить "настоящую" транзу,
     * а не просто отменить её). Важно: ПАРАМЕТРЫ должны совпадать с оригиналом (кроме комиссии),
     * иначе узел может не принять как replace.
     */
    public static String speedUpLegacyReplace(
            Web3j web3j,
            Credentials creds,
            long chainId,
            BigInteger nonce,
            BigInteger gasPrice,
            String to,
            BigInteger valueWei
    ) throws Exception {
        BigInteger gasLimit = BigInteger.valueOf(21_000);
        RawTransaction tx = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, to, valueWei);
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, creds);
        String rawHex = Numeric.toHexString(signed);
        EthSendTransaction resp = web3j.ethSendRawTransaction(rawHex).send();
        if (resp.hasError()) throw new RuntimeException(resp.getError().getMessage());
        return resp.getTransactionHash();
    }
}