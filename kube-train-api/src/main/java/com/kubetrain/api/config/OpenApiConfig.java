package com.kubetrain.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration OpenAPI / Swagger UI.
 *
 * Accessible sur :
 *  - Swagger UI : http://localhost:8080/swagger-ui.html
 *  - JSON spec : http://localhost:8080/v3/api-docs
 *  - YAML spec : http://localhost:8080/v3/api-docs.yaml
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kubeTrainOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("🚄 Kube-Train API")
                        .description("API de réservation de billets de train — Projet de formation Kubernetes / Cloud-Native")
                        .version("2.0.0")
                        .contact(new Contact()
                                .name("Sami Yanez-Carbonell")
                                .url("https://github.com/samiyc/kube-train")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local"),
                        new Server().url("http://104.155.124.69").description("GKE Autopilot")
                ));
    }
}
