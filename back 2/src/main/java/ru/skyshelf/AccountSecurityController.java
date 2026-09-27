package ru.skyshelf;

import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/security")
class AccountSecurityController {
    private final AuthController auth;private final Accounts accounts;private final PasswordEncoder encoder;
    private final CryptoService crypto;private final TotpService totp;private final AuditService audit;
    AccountSecurityController(AuthController a,Accounts accounts,PasswordEncoder e,CryptoService c,TotpService t,AuditService audit){auth=a;this.accounts=accounts;encoder=e;crypto=c;totp=t;this.audit=audit;}

    record CodeInput(String code) {}
    record DisableInput(String password,String code) {}
    record PasswordInput(String currentPassword,String newPassword) {}

    @GetMapping("/overview")
    Map<String,Object> overview(Authentication authentication){
        Account account=auth.current(authentication);Map<String,Object> out=new LinkedHashMap<>();out.put("twoFactorEnabled",account.twoFactorEnabled);out.put("lastLoginAt",account.lastLoginAt);out.put("passwordAlgorithm","Argon2id");out.put("fileEncryption","AES-256-GCM");out.put("integrity","SHA-256");out.put("shareTokens","SHA-256, исходный токен не хранится");out.put("session","HttpOnly + SameSite=Lax + CSRF");return out;
    }

    @PostMapping("/2fa/setup")
    Map<String,String> setup(Authentication authentication) throws Exception {
        Account account=auth.current(authentication);if(account.twoFactorEnabled)throw StorageService.bad("Двухфакторная защита уже включена.");
        String secret=totp.newSecret();account.twoFactorSecret=crypto.encryptText(secret);accounts.save(account);audit.record(account.id,null,"TWO_FACTOR_SETUP_STARTED","ACCOUNT",account.id,"Секрет защищён AES-GCM");return Map.of("secret",secret,"uri",totp.uri(account.email,secret));
    }

    @PostMapping("/2fa/enable")
    Map<String,Boolean> enable(Authentication authentication,@RequestBody CodeInput input) throws Exception {
        Account account=auth.current(authentication);if(account.twoFactorSecret==null)throw StorageService.bad("Сначала создайте секрет 2FA.");String secret=crypto.decryptText(account.twoFactorSecret);if(!totp.valid(secret,input.code()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный одноразовый код.");account.twoFactorEnabled=true;accounts.save(account);audit.record(account.id,null,"TWO_FACTOR_ENABLED","ACCOUNT",account.id,"TOTP RFC 6238");return Map.of("enabled",true);
    }

    @PostMapping("/2fa/disable")
    Map<String,Boolean> disable(Authentication authentication,@RequestBody DisableInput input) throws Exception {
        Account account=auth.current(authentication);if(!encoder.matches(input.password()==null?"":input.password(),account.passwordHash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный текущий пароль.");
        if(account.twoFactorEnabled&&(account.twoFactorSecret==null||!totp.valid(crypto.decryptText(account.twoFactorSecret),input.code())))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный одноразовый код.");account.twoFactorEnabled=false;account.twoFactorSecret=null;accounts.save(account);audit.record(account.id,null,"TWO_FACTOR_DISABLED","ACCOUNT",account.id,"");return Map.of("enabled",false);
    }

    @PostMapping("/password")
    Map<String,Object> password(Authentication authentication,@RequestBody PasswordInput input){
        Account account=auth.current(authentication);String next=input.newPassword()==null?"":input.newPassword();if(!encoder.matches(input.currentPassword()==null?"":input.currentPassword(),account.passwordHash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный текущий пароль.");if(next.length()<10||next.length()>128)throw StorageService.bad("Новый пароль: от 10 до 128 символов.");if(encoder.matches(next,account.passwordHash))throw StorageService.bad("Новый пароль должен отличаться от текущего.");account.passwordHash=encoder.encode(next);accounts.save(account);audit.record(account.id,null,"PASSWORD_CHANGED","ACCOUNT",account.id,"Argon2id · "+Instant.now());return Map.of("ok",true,"changedAt",Instant.now());
    }
}

@RestController @RequestMapping("/api/activity")
class ActivityController {
    private final AuthController auth;private final AuditService audit;
    ActivityController(AuthController a,AuditService audit){auth=a;this.audit=audit;}
    @GetMapping List<Map<String,Object>> activity(Authentication authentication,@RequestParam(defaultValue="50") int limit){return audit.visible(auth.current(authentication).id,limit);}
}
