package com.kubetrain.api.event;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Événement émis à chaque nouvelle réservation.
 *
 * 🎯 Pourquoi un Event séparé du DTO ReservationResponse ?
 *  - Le DTO est le CONTRAT avec le client HTTP (ce qu'il voit)
 *  - L'Event est le CONTRAT avec les consumers Kafka (ce qu'ils reçoivent)
 *  - Ils peuvent évoluer indépendamment (ex: ajouter un champ dans l'event sans changer l'API)
 *
 * 🎯 Pourquoi un eventId ?
 *  - Permet l'idempotence côté consumer : si le même event est reçu 2 fois,
 *    le consumer peut ignorer le doublon grâce à l'eventId unique.
 *  - Kafka garantit "at-least-once" delivery → les doublons sont possibles.
 */
@Builder
public record ReservationEvent(
        String eventId,
        String reservationId,
        String trainId,
        String passengerName,
        BigDecimal price,
        Instant createdAt
) {}
