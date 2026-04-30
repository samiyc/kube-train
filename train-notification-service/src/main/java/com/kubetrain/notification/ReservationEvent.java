package com.kubetrain.notification;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Copie locale de l'événement publié par kube-train-api.
 *
 * 🎯 Pourquoi dupliquer la classe et ne pas la partager ?
 *  - En microservices, chaque service est INDÉPENDANT (pas de lib partagée)
 *  - Le producer et le consumer peuvent évoluer à des rythmes différents
 *  - Si le producer ajoute un champ, le consumer peut l'ignorer (rétro-compatible)
 *  - C'est le contrat JSON qui compte, pas la classe Java
 *
 * 🎯 En vrai (entreprise) :
 *  - On utilise un schema registry (Avro/Protobuf) pour valider la compatibilité
 *  - Ou un contrat OpenAPI / AsyncAPI partagé (mais pas le code Java)
 */
public record ReservationEvent(
        String eventId,
        String reservationId,
        String trainId,
        String passengerName,
        BigDecimal price,
        Instant createdAt
) {}
