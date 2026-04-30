package com.kubetrain.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Informations sur un train disponible")
public record TrainResponse(
        @Schema(description = "Identifiant du train", example = "TGV-7042")
        String id,

        @Schema(description = "Gare de départ", example = "Paris Gare du Nord")
        String origin,

        @Schema(description = "Gare d'arrivée", example = "Lille Europe")
        String destination,

        @Schema(description = "Prix du billet en euros", example = "29.90")
        BigDecimal price,

        @Schema(description = "Nombre de places disponibles", example = "142")
        int availableSeats
) {}
