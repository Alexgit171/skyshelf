package ru.skyshelf;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Минимальная реализация RFC 6238 для приложений-аутентификаторов. */
@Service
class TotpService {
    private static final char[] BASE32="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random=new SecureRandom();

    String newSecret() {byte[] bytes=new byte[20];random.nextBytes(bytes);return encode(bytes);}

    boolean valid(String secret,String supplied) {
        if(supplied==null||!supplied.matches("\\d{6}")) return false;
        long window=Instant.now().getEpochSecond()/30;
        for(long offset=-1;offset<=1;offset++) if(code(secret,window+offset).equals(supplied)) return true;
        return false;
    }

    String uri(String email,String secret) {
        String label=URLEncoder.encode("SkyShelf:"+email,StandardCharsets.UTF_8);
        return "otpauth://totp/"+label+"?secret="+secret+"&issuer=SkyShelf&algorithm=SHA1&digits=6&period=30";
    }

    String code(String secret,long counter) {
        try {
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret),"HmacSHA1"));
            byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset=hash[hash.length-1]&0x0f;
            int binary=((hash[offset]&0x7f)<<24)|((hash[offset+1]&0xff)<<16)|((hash[offset+2]&0xff)<<8)|(hash[offset+3]&0xff);
            return String.format("%06d",binary%1_000_000);
        } catch(Exception e){throw new IllegalStateException(e);}
    }

    private static String encode(byte[] bytes) {
        StringBuilder out=new StringBuilder();int buffer=0,bits=0;
        for(byte value:bytes){buffer=(buffer<<8)|(value&0xff);bits+=8;while(bits>=5){out.append(BASE32[(buffer>>(bits-5))&31]);bits-=5;}}
        if(bits>0) out.append(BASE32[(buffer<<(5-bits))&31]);return out.toString();
    }

    private static byte[] decode(String value) {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
        for(char ch:value.replace("=","").toUpperCase().toCharArray()){
            int index="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(ch);if(index<0) throw new IllegalArgumentException("base32");
            buffer=(buffer<<5)|index;bits+=5;if(bits>=8){out.write((buffer>>(bits-8))&255);bits-=8;}
        }
        return out.toByteArray();
    }
}
