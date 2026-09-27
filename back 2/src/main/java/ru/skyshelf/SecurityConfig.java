package ru.skyshelf;

import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder encoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean UserDetailsService userDetails(Accounts accounts) {
        return email -> {
            Account a=accounts.findByEmail(email).orElseThrow(()->new UsernameNotFoundException("Account"));
            return User.withUsername(a.email).password(a.passwordHash).roles(a.role).disabled(a.blocked).build();
        };
    }
    @Bean AuthenticationManager authManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider=new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http
            .csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .securityContext(c->c.securityContextRepository(new HttpSessionSecurityContextRepository()))
            .headers(h->h
                .frameOptions(f->f.deny())
                .contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .addHeaderWriter((request,response)->{
                    response.setHeader("Referrer-Policy","no-referrer");
                    response.setHeader("Permissions-Policy","camera=(), microphone=(), geolocation=(), payment=()");
                    response.setHeader("Cross-Origin-Resource-Policy","same-site");
                }))
            .requestCache(c->c.disable())
            .authorizeHttpRequests(a->a
                .requestMatchers("/api/health","/api/auth/csrf","/api/auth/login","/api/auth/register","/api/public/**","/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e->e
                .authenticationEntryPoint((q,r,x)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"Войдите в аккаунт\"}");})
                .accessDeniedHandler((q,r,x)->{r.setStatus(403);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"Доступ запрещён или сессия устарела. Обновите страницу.\"}");}))
            .logout(l->l.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable())
            .build();
    }
}
