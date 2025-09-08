package com.cryptrofilter;
import com.cryptrofilter.ethService.TransactionPoolingService;
import com.cryptrofilter.ethService.TransactionStreamService;

public class Main {
    public static void main(String[] args) {
//        TransactionPoolingService ethService = new TransactionPoolingService();
        TransactionStreamService ethService = new TransactionStreamService();
        ethService.connect();
    }
}
