package com.cryptrofilter.ethGenService.libs;

import org.bouncycastle.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

public class Encryptor {
    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] encryptAesGcm(byte[] plaintext, char[] pass) {
        try {
            byte[] salt = new byte[16]; RNG.nextBytes(salt);
            byte[] iv   = new byte[12]; RNG.nextBytes(iv);
            byte[] key  = kdf(pass, salt);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext);

            byte[] out = new byte[16 + 12 + ct.length];
            System.arraycopy(salt, 0, out, 0, 16);
            System.arraycopy(iv,   0, out, 16, 12);
            System.arraycopy(ct,   0, out, 28, ct.length);

            Arrays.fill(key, (byte) 0);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] kdf(char[] pass, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, 200_000, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(spec).getEncoded();
    }
}
