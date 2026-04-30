package com.kubetrain.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Confirmation de réservation.
 *
 * 🎯 @Builder sur un record : Lombok génère un builder typé
 *    qui appelle le constructeur canonique du record.
 *    → Évite la confusion des 6 paramètres positionnels
 *    → Le record reste immutable (pas de setter)
 *
 * Exemple d'utilisation :
 *   ReservationResponse.builder()
 *       .reservationId("RES-A1B2C3D4")
 *       .status("CONFIRMED")
 *       .trainId("TGV-7042")
 *       .build();
 */
@Builder
@Schema(description = "Confirmation de réservation d'un billet de train")
public record ReservationResponse(
        @Schema(description = "Identifiant unique de la réservation", example = "RES-20260430-001")
        String reservationId,

        @Schema(description = "Statut de la réservation", example = "CONFIRMED")
        String status,

        @Schema(description = "Identifiant du train", example = "TGV-7042")
        String trainId,

        @Schema(description = "Numéro du wagon", example = "Wagon 7")
        String wagon,

        @Schema(description = "Date et heure de départ (UTC)", example = "2026-04-30T14:30:00Z")
        Instant departureTime,

        @Schema(description = "Prix du billet en euros", example = "29.90")
        BigDecimal price
) {}
