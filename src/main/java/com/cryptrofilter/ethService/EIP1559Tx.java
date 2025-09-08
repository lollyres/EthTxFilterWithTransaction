package com.cryptrofilter.ethService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class EIP1559Tx {
    private static final Logger log = LoggerFactory.getLogger(EIP1559Tx.class);

    private static final String URL = "https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5";
    private static final HttpService http = new HttpService(URL); // <— сохраняем, чтобы слать сырые RPC
    private static final Web3j web3j = Web3j.build(http);

    private static final long CHAIN_ID = 1L;

    private static final BigInteger ONE_GWEI = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger TIP_MIN = BigInteger.valueOf(50_000_000L);  // 0.05 gwei
    private static final BigInteger TIP_START = BigInteger.valueOf(100_000_000L); // 0.1 gwei

    // локальный менеджер nonce по адресу
    private final ConcurrentHashMap<String, BigInteger> localNonce = new ConcurrentHashMap<>();

    private BigInteger getPendingBaseFeeWei() throws Exception {
        Request<?, RawJson> req = new Request<>("eth_getBlockByNumber", java.util.Arrays.asList("pending", Boolean.FALSE), http, // используем HttpService напрямую
                RawJson.class);
        RawJson res = req.send();
        JsonNode block = res.getRaw();                // весь JSON
        JsonNode baseFeeHex = block.get("baseFeePerGas");
        if (baseFeeHex != null && !baseFeeHex.isNull()) {
            return Numeric.decodeQuantity(baseFeeHex.asText());
        }
        // если вдруг нода без London — откатимся на gasPrice
        return web3j.ethGasPrice().send().getGasPrice();
    }

    public static class RawJson extends Response<JsonNode> {
        public JsonNode getRaw() {
            return getResult();
        }
    }

    private String sendEip1559Transaction(Credentials cred, String to, BigInteger valueWei, BigInteger gasLimit, BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) throws Exception {
        // 1) nonce (pending)
        BigInteger nonce = web3j.ethGetTransactionCount(cred.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();

        byte[] toBytes = Numeric.hexStringToByteArray(to);
        byte[] data = new byte[0]; // пустые input

        // 2) RLP для "signing payload": [chainId, nonce, maxPrio, maxFee, gasLimit, to, value, data, accessList=[]]
        RlpList unsignedTx = new RlpList(java.util.Arrays.asList(RlpString.create(BigInteger.valueOf(CHAIN_ID)), RlpString.create(nonce), RlpString.create(maxPriorityFeePerGas), RlpString.create(maxFeePerGas), RlpString.create(gasLimit), RlpString.create(toBytes), RlpString.create(valueWei), RlpString.create(data), new RlpList() // пустой accessList
        ));

        byte[] encodedUnsigned = RlpEncoder.encode(unsignedTx);

        // 3) EIP-1559 требует префикс 0x02 перед keccak
        byte[] payloadForSig = new byte[1 + encodedUnsigned.length];
        payloadForSig[0] = 0x02;
        System.arraycopy(encodedUnsigned, 0, payloadForSig, 1, encodedUnsigned.length);

        byte[] messageHash = Hash.sha3(payloadForSig);

        // 4) подпись
        Sign.SignatureData sig = Sign.signMessage(messageHash, cred.getEcKeyPair(), false);
        // yParity (v) должен быть 0/1
        int yParity = (sig.getV()[0] & 0x01);

        // 5) окончательный RLP: [chainId, nonce, maxPrio, maxFee, gasLimit, to, value, data, accessList, yParity, r, s]
        RlpList signedTx = new RlpList(java.util.Arrays.asList(RlpString.create(BigInteger.valueOf(CHAIN_ID)), RlpString.create(nonce), RlpString.create(maxPriorityFeePerGas), RlpString.create(maxFeePerGas), RlpString.create(gasLimit), RlpString.create(toBytes), RlpString.create(valueWei), RlpString.create(data), new RlpList(), RlpString.create(BigInteger.valueOf(yParity)), RlpString.create(new BigInteger(1, sig.getR())), RlpString.create(new BigInteger(1, sig.getS()))));

        byte[] encodedSigned = RlpEncoder.encode(signedTx);

        // 6) добавляем тип 0x02 в начало
        byte[] raw = new byte[1 + encodedSigned.length];
        raw[0] = 0x02;
        System.arraycopy(encodedSigned, 0, raw, 1, encodedSigned.length);

        String rawHex = Numeric.toHexString(raw);

        EthSendTransaction resp = web3j.ethSendRawTransaction(rawHex).send();
        if (resp.hasError()) {
            throw new RuntimeException(resp.getError().getMessage());
        }
        return resp.getTransactionHash();
    }

    public boolean sendZeroTxEip1559(String fromPriv, String toAddr) {
        try {
            Credentials cred = Credentials.create(fromPriv);
            log.info("Credentials created");

            BigInteger gasLimit = BigInteger.valueOf(21_000);
            BigInteger base = getPendingBaseFeeWei();
            log.info("Credentials created");

            // минимальный tip (0.05–0.1 gwei) и щедрый потолок
            BigInteger tip  = TIP_START;                       // 0.1 gwei
            BigInteger max  = base.multiply(BigInteger.valueOf(2)).add(tip);

            String txHash = sendEip1559WithRetries(cred, toAddr, BigInteger.ZERO, gasLimit, tip, max);
            TransactionReceipt rc = waitForReceipt(txHash);
            log.info("zero1559 mined: {} status={} block={}", txHash, rc.getStatus(), rc.getBlockNumber());
            return "0x1".equalsIgnoreCase(rc.getStatus());
        } catch (Exception e) {
            log.error("sendZeroTxEip1559 failed", e);
            return false;
        }
    }

    public String submitZeroTxEip1559(String fromPriv, String toAddr) {
        try {
            Credentials cred = Credentials.create(fromPriv);

            BigInteger gasLimit = BigInteger.valueOf(21_000);
            BigInteger base = getPendingBaseFeeWei();
            BigInteger tip  = TIP_START;                              // 0.1 gwei
            BigInteger max  = base.multiply(BigInteger.valueOf(2)).add(tip);

            String txHash = sendEip1559WithRetries(cred, toAddr, BigInteger.ZERO, gasLimit, tip, max);
            log.info("zero1559 submitted: {}", txHash);
            return txHash;
        } catch (Exception e) {
            log.error("submitZeroTxEip1559 failed", e);
            return null;
        }
    }

    private String sendEip1559Once(Credentials cred, BigInteger nonce, String to, BigInteger valueWei, BigInteger gasLimit, BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) throws Exception {
        byte[] toBytes = Numeric.hexStringToByteArray(to);
        byte[] data = new byte[0];

        RlpList unsignedTx = new RlpList(Arrays.asList(RlpString.create(BigInteger.valueOf(CHAIN_ID)), RlpString.create(nonce), RlpString.create(maxPriorityFeePerGas), RlpString.create(maxFeePerGas), RlpString.create(gasLimit), RlpString.create(toBytes), RlpString.create(valueWei), RlpString.create(data), new RlpList() // empty accessList
        ));

        byte[] encUnsigned = RlpEncoder.encode(unsignedTx);
        byte[] prefixed = new byte[1 + encUnsigned.length];
        prefixed[0] = 0x02;
        System.arraycopy(encUnsigned, 0, prefixed, 1, encUnsigned.length);

        byte[] hash = Hash.sha3(prefixed);
        Sign.SignatureData sig = Sign.signMessage(hash, cred.getEcKeyPair(), false);
        int yParity = (sig.getV()[0] & 0x01);

        RlpList signedTx = new RlpList(Arrays.asList(RlpString.create(BigInteger.valueOf(CHAIN_ID)), RlpString.create(nonce), RlpString.create(maxPriorityFeePerGas), RlpString.create(maxFeePerGas), RlpString.create(gasLimit), RlpString.create(toBytes), RlpString.create(valueWei), RlpString.create(data), new RlpList(), RlpString.create(BigInteger.valueOf(yParity)), RlpString.create(new BigInteger(1, sig.getR())), RlpString.create(new BigInteger(1, sig.getS()))));

        byte[] encSigned = RlpEncoder.encode(signedTx);
        byte[] raw = new byte[1 + encSigned.length];
        raw[0] = 0x02;
        System.arraycopy(encSigned, 0, raw, 1, encSigned.length);

        EthSendTransaction resp = web3j.ethSendRawTransaction(Numeric.toHexString(raw)).send();
        if (resp.hasError()) throw new RuntimeException(resp.getError().getMessage());
        return resp.getTransactionHash();
    }

    private String sendEip1559WithRetries(Credentials cred, String to, BigInteger valueWei, BigInteger gasLimit, BigInteger tip, BigInteger maxFee) throws Exception {
        final String from = cred.getAddress();
        int attempt = 0;

        while (true) {
            BigInteger nonce = reserveNextNonce(from);
            try {
                return sendEip1559Once(cred, nonce, to, valueWei, gasLimit, tip, maxFee);
            } catch (RuntimeException ex) {
                String m = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();

                // nonce-ошибки → пересинхронизировать и повторить
                if (m.contains("invalid nonce") || m.contains("nonce too low") || m.contains("already known") || m.contains("same hash was already imported") || m.contains("nonce too high")) {
                    reconcileNonce(from);
                    Thread.sleep(200L * Math.min(++attempt, 10));
                    continue;
                }

                // под baseFee не попали / слишком дёшево → обновить baseFee и чуть поднять tip
                if (m.contains("underpriced") || m.contains("fee too low") || m.contains("max fee per gas less than block base fee")) {
                    BigInteger base = getPendingBaseFeeWei();
                    // replacement требует ≥10% выше приоритета
                    tip = tip.max(TIP_MIN).add(tip.divide(BigInteger.TEN)).max(TIP_MIN); // +10%
                    // потолок щедрый, фактически заплатим только baseFee+tip
                    maxFee = base.multiply(BigInteger.valueOf(2)).add(tip);
                    Thread.sleep(500L * Math.min(++attempt, 10));
                    continue;
                }

                // другие ошибки
                throw ex;
            }
        }
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        var processor = new PollingTransactionReceiptProcessor(
                web3j,
                20000,
                40
        );
        return processor.waitForTransactionReceipt(txHash);
    }

    private BigInteger getFreshPendingNonce(String from) throws Exception {
        return web3j.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
    }

    private synchronized BigInteger reserveNextNonce(String from) throws Exception {
        BigInteger net = getFreshPendingNonce(from);
        BigInteger loc = localNonce.getOrDefault(from, net);
        BigInteger next = net.max(loc);
        localNonce.put(from, next.add(BigInteger.ONE));
        return next;
    }

    private void reconcileNonce(String from) {
        try {
            BigInteger net = getFreshPendingNonce(from);
            localNonce.put(from, net);
        } catch (Exception ignore) {
        }
    }
}
