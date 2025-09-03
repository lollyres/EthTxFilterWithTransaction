package com.cryptrofilter.ethGenService.records;

import java.math.BigInteger;

public record Hit(byte[] addr20, String checksum, BigInteger priv) {}
