package com.template.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            // CSRF protection defends cookie-based sessions. This is a stateless JWT
            // resource server: every request carries an Authorization: Bearer token
            // and no cookie is ever used for auth, so a forged cross-site request has
            // no credential to ride on. (The web app's cookie lives on the BFF, which
            // is where browser-facing CSRF defence belongs.) CodeQL's
            // java/spring-disabled-csrf-protection flags this; it is a false positive.
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/health").permitAll()
                .pathMatchers("/connect/**").authenticated()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }
}
