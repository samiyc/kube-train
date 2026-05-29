package com.kubetrain.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration Spring Security — Mode permissif (profil par défaut, sans "secured").
 *
 * Tous les endpoints sont ouverts. Les headers OWASP sont ajoutés quand même
 * (bonne pratique : défense en profondeur même en dev).
 *
 * Activé quand : profil "secured" n'est PAS actif.
 * Usage : développement local sans Keycloak, tests existants, CI.
 */
@Configuration
@EnableWebSecurity
@Profile("!secured")
public class PermissiveSecurityConfig {

    @Bean
    @SuppressWarnings({"java:S112", "java:S1130"})  // throws Exception requis par l'API Spring Security
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // OWASP Security Headers (même en mode permissif)
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())     // X-Content-Type-Options: nosniff
                .frameOptions(frame -> frame.deny())                // X-Frame-Options: DENY
            )
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
