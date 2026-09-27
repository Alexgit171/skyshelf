package ru.skyshelf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Прикладное шифрование объектов: AES-256-GCM + SHA-256 для проверки целостности. */
@Service
class CryptoService {
    private static final byte[] MAGIC=new byte[]{'S','K','Y','2'};
    private static final int NONCE_SIZE=12;
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();

    CryptoService(@Value("${app.crypto.master-key}") String encoded) {
        try {
            byte[] raw=Base64.getDecoder().decode(encoded);
            if(raw.length!=32) throw new IllegalArgumentException("master key must be 32 bytes");
            key=new SecretKeySpec(raw,"AES");
        } catch(Exception e) {
            throw new IllegalStateException("SKYSHELF_MASTER_KEY must contain a Base64 encoded 32-byte key",e);
        }
    }

    byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] nonce=new byte[NONCE_SIZE];random.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(MAGIC);
        byte[] encrypted=cipher.doFinal(plain);
        return ByteBuffer.allocate(MAGIC.length+nonce.length+encrypted.length).put(MAGIC).put(nonce).put(encrypted).array();
    }

    byte[] decrypt(byte[] envelope) throws GeneralSecurityException {
        if(!isEncrypted(envelope)) return envelope; // миграция объектов старого формата
        byte[] nonce=Arrays.copyOfRange(envelope,MAGIC.length,MAGIC.length+NONCE_SIZE);
        byte[] encrypted=Arrays.copyOfRange(envelope,MAGIC.length+NONCE_SIZE,envelope.length);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(MAGIC);
        return cipher.doFinal(encrypted);
    }

    boolean isEncrypted(byte[] bytes) {
        return bytes.length>MAGIC.length+NONCE_SIZE+16 && Arrays.equals(MAGIC,Arrays.copyOf(bytes,MAGIC.length));
    }

    String encryptText(String value) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(encrypt(value.getBytes(StandardCharsets.UTF_8)));
    }

    String decryptText(String value) throws GeneralSecurityException {
        return new String(decrypt(Base64.getDecoder().decode(value)),StandardCharsets.UTF_8);
    }

    static String checksum(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    static String tokenHash(String token) {
        try {return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    String newToken() {byte[] value=new byte[32];random.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
}
