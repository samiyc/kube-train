package com.kubetrain.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Demande de réservation d'un billet")
public record CreateReservationRequest(
        @NotBlank(message = "Le nom du passager est obligatoire")
        @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
        @Schema(description = "Nom du passager", example = "Jean Dupont")
        String passengerName,

        @NotBlank(message = "L'identifiant du train est obligatoire")
        @Schema(description = "Identifiant du train à réserver", example = "TGV-7042")
        String trainId
) {}
