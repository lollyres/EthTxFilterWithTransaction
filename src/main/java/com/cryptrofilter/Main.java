package com.cryptrofilter;
import com.cryptrofilter.ethService.SpeedUpUtils;
import com.cryptrofilter.ethService.TransactionStreamService;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;

public class Main {
    private static final String URL = "https://mainnet.infura.io/v3/6096c2acec854ec99f8b6945bda33ce5";
    private static final Web3j web3j = Web3j.build(new HttpService(URL));
    private static final Credentials BANK_CRED = Credentials.create("0x43d445f3327606850e3507468849a4a4cc8e5809364759a44e172820ebb2c3a6");

    public static void main(String[] args) {
        TransactionStreamService ethService = new TransactionStreamService();
        ethService.connect();

//        try {
//            var latest  = web3j.ethGetTransactionCount(BANK_CRED.getAddress(), DefaultBlockParameterName.LATEST ).send().getTransactionCount();
//            var pending = web3j.ethGetTransactionCount(BANK_CRED.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
//            System.out.println("NONCE latest="+latest+", pending="+pending);
//
//            BigInteger nonce = BigInteger.valueOf(61);
//            BigInteger gasPrice = BigInteger.valueOf(5_000_000_000L); // 5 gwei
//
//            String txHash = SpeedUpUtils.speedUpLegacyCancel(
//                    web3j,
//                    BANK_CRED,
//                    1L,         // mainnet
//                    nonce,
//                    gasPrice
//            );
//            System.out.println("replace/cancel tx: " + txHash);
//            int count = 60;
//            while (count < 62) {
//                BigInteger nonce = BigInteger.valueOf(count);
//                BigInteger gasPrice = BigInteger.valueOf(5_000_000_000L); // 5 gwei
//
//                String txHash = SpeedUpUtils.speedUpLegacyCancel(
//                        web3j,
//                        BANK_CRED,
//                        1L,         // mainnet
//                        nonce,
//                        gasPrice
//                );
//                System.out.println("replace/cancel tx: " + txHash);
//                count++;
//            }
//        } catch (Exception e) {
//            System.err.println(e.getMessage());
//        }
    }
}
