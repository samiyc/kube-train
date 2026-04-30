package com.kubetrain.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Réponse de la page d'accueil")
public record WelcomeResponse(
        @Schema(description = "Message d'accueil (provient de la ConfigMap)", example = "🚄 Bienvenue à bord !")
        String message,

        @Schema(description = "Nom du pod Kubernetes qui a répondu", example = "kube-train-deployment-7479c7556c-fvx58")
        String wagon
) {}
