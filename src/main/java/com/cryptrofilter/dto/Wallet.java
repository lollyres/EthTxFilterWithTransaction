package com.cryptrofilter.dto;

public class Wallet {
    private final String address;
    private final String privateKey;

    public Wallet(String address, String privateKey) {
        this.address = address;
        this.privateKey = privateKey;
    }

    public String getAddress() {
        return address;
    }

    public String getPrivateKey() {
        return privateKey;
    }
}
