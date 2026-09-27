package ru.skyshelf;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
class AuthController {
    private final Accounts accounts; private final PasswordEncoder encoder; private final AuthenticationManager manager;
    private final CryptoService crypto;private final TotpService totp;private final AuditService audit;
    private final Map<String,Attempt> attempts=new ConcurrentHashMap<>();
    private record Attempt(int count,long until) {}
    record Login(@NotBlank @Email @Size(max=180) String email,@NotBlank @Size(max=128) String password,@Pattern(regexp="\\d{6}") String code) {}
    record Register(@NotBlank @Size(max=80) String name,@NotBlank @Email @Size(max=180) String email,@Size(min=10,max=128) @NotNull String password) {}
    record Profile(@NotBlank @Size(max=80) String name,@Pattern(regexp="dark|light") @NotNull String theme,@Pattern(regexp="blue|violet|mint") @NotNull String accent) {}
    AuthController(Accounts a,PasswordEncoder e,AuthenticationManager m,CryptoService c,TotpService t,AuditService audit) {accounts=a;encoder=e;manager=m;crypto=c;totp=t;this.audit=audit;}
    @GetMapping("/health") Map<String,Object> health() {return Map.of("status","ok","version","1.0.0","encryption","AES-256-GCM");}
    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) {return Map.of("token",token.getToken(),"header",token.getHeaderName());}
    static String email(String s) {return s.strip().toLowerCase(Locale.ROOT);}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    synchronized Map<String,Object> register(@Valid @RequestBody Register input,HttpServletRequest req) {
        limit("register:"+req.getRemoteAddr(),20);
        if(accounts.findByEmail(email(input.email())).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Этот email уже зарегистрирован.");
        Account a=new Account();a.name=input.name().strip();a.email=email(input.email());a.passwordHash=encoder.encode(input.password());accounts.save(a);audit.record(a.id,null,"ACCOUNT_REGISTERED","ACCOUNT",a.id,a.email);
        return view(a);
    }
    @PostMapping("/auth/login")
    Map<String,Object> login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        String login=email(input.email());
        limit("ip:"+request.getRemoteAddr(),60); limit("email:"+login,10);
        try {
            Authentication auth=manager.authenticate(new UsernamePasswordAuthenticationToken(login,input.password()));
            Account account=accounts.findByEmail(login).orElseThrow();
            if(account.twoFactorEnabled){
                try {if(account.twoFactorSecret==null||!totp.valid(crypto.decryptText(account.twoFactorSecret),input.code()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Введите корректный 6-значный код 2FA.");}
                catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Не удалось проверить двухфакторную защиту.");}
            }
            if(request.getSession(false)!=null) request.changeSessionId();
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context,request,response);
            attempts.remove("email:"+login);account.lastLoginAt=Instant.now();accounts.save(account);audit.record(account.id,null,"LOGIN_SUCCEEDED","ACCOUNT",account.id,"Вход в защищённую сессию");
            return view(account);
        } catch(AuthenticationException e) {throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный email или пароль, либо аккаунт заблокирован.");}
    }
    private synchronized void limit(String key,int max) {
        long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue().until()<now);
        if(attempts.size()>10000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Повторите попытку позже.");
        Attempt old=attempts.getOrDefault(key,new Attempt(0,now+600000));
        if(old.count()>=max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Слишком много попыток. Попробуйте через 10 минут.");
        attempts.put(key,new Attempt(old.count()+1,old.until()));
    }
    @PostMapping("/auth/logout") Map<String,Boolean> logout(HttpServletRequest req) {
        if(req.getSession(false)!=null) req.getSession(false).invalidate();SecurityContextHolder.clearContext();return Map.of("ok",true);
    }
    @GetMapping("/me") Map<String,Object> me(Authentication auth) {return view(current(auth));}
    @PatchMapping("/me") Map<String,Object> profile(Authentication auth,@Valid @RequestBody Profile p) {
        Account a=current(auth);a.name=p.name().strip();a.theme=p.theme();a.accent=p.accent();accounts.save(a);audit.record(a.id,null,"PROFILE_UPDATED","ACCOUNT",a.id,"Профиль и оформление");return view(a);
    }
    Account current(Authentication auth) {
        if(auth==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Войдите в аккаунт");
        Account a=accounts.findByEmail(auth.getName()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Аккаунт не найден"));
        if(a.blocked) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Аккаунт заблокирован администратором.");
        return a;
    }
    static Map<String,Object> view(Account a) {Map<String,Object> out=new LinkedHashMap<>();out.put("id",a.id);out.put("email",a.email);out.put("name",a.name);out.put("role",a.role);out.put("plan",a.plan);out.put("theme",a.theme);out.put("accent",a.accent);out.put("blocked",a.blocked);out.put("twoFactorEnabled",a.twoFactorEnabled);out.put("lastLoginAt",a.lastLoginAt);out.put("createdAt",a.createdAt);return out;}
}
