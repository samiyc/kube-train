package com.kubetrain.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration Spring Security — Mode sécurisé (profil "secured").
 *
 * 🎯 OAuth2 Resource Server : l'API valide les JWT émis par Keycloak (local) ou un IdP.
 *    - Endpoints publics : GET /, /trains/**, /reservations/**, /actuator/**, /swagger-ui/**
 *    - Endpoints protégés : POST /reservations, GET /secure → JWT valide requis
 *
 * 🎯 Question entretien :
 *  "Quelle est la différence entre Resource Server et Client ?"
 *  → Resource Server = valide les tokens (notre API)
 *  → Client = obtient les tokens (front-end, Postman)
 *  → Authorization Server = émet les tokens (Keycloak, Google, Auth0)
 */
@Configuration
@EnableWebSecurity
@Profile("secured")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless : pas de session HTTP (chaque requête porte son JWT)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF désactivé : pas de cookie = pas de risque CSRF (API stateless)
            .csrf(csrf -> csrf.disable())
            // OWASP Security Headers
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())     // X-Content-Type-Options: nosniff
                .frameOptions(frame -> frame.deny())                // X-Frame-Options: DENY
            )
            // Règles d'autorisation
            .authorizeHttpRequests(auth -> auth
                // Endpoints publics (lecture seule)
                .requestMatchers(HttpMethod.GET, "/", "/trains", "/trains/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/reservations/**").permitAll()
                // Actuator, Swagger, OpenAPI
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Tout le reste nécessite un JWT valide
                .anyRequest().authenticated()
            )
            // OAuth2 Resource Server : validation JWT via l'issuer-uri configuré
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
