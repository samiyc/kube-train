package com.kubetrain.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer Kafka qui traite les événements de réservation.
 *
 * 🎯 Pourquoi StringDeserializer + ObjectMapper ?
 *  Spring Kafka 4.0.0 JsonDeserializer référence encore Jackson 2 (com.fasterxml),
 *  mais Spring Boot 4 utilise Jackson 3 (tools.jackson). On reçoit le JSON en String
 *  et on désérialise manuellement — c'est un pattern courant en production car il
 *  donne plus de contrôle sur la gestion d'erreur de désérialisation.
 *
 * 🎯 Idempotence (at-least-once → exactly-once sémantique) :
 *  Kafka garantit "at-least-once" delivery : un message peut être reçu 2+ fois
 *  (rebalancing, retry, crash du consumer avant commit offset).
 *  On stocke les eventId déjà traités pour ignorer les doublons.
 *
 *  ⚠️ En prod : utiliser une BDD (Redis, PostgreSQL) au lieu d'un Set en mémoire,
 *  car le Set est perdu au redémarrage du service.
 *  Pattern courant : table "processed_events(event_id PK, processed_at)" avec un UPSERT.
 *
 * 🎯 Question entretien Kafka — Consumer Groups :
 *  "Que se passe-t-il si tu as 3 partitions et 2 consumers dans le même group ?"
 *  → Un consumer reçoit 2 partitions, l'autre 1. Si tu ajoutes un 3ème → 1 chacun.
 *  → Si tu as 4 consumers → 1 reste idle (plus de consumers que de partitions = gaspillage)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventConsumer {

    private final ObjectMapper objectMapper;

    // Cache des eventId déjà traités (démo seulement — en prod, utiliser Redis/BDD)
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    @KafkaListener(topics = "train-reservations", groupId = "notification-group")
    public void handleReservation(String payload) {
        ReservationEvent event = deserialize(payload);

        // Idempotence : ignorer les doublons
        if (!processedEventIds.add(event.eventId())) {
            log.warn("⏭️ Événement déjà traité, ignoré : eventId={}, reservationId={}",
                    event.eventId(), event.reservationId());
            return;
        }

        log.info("📬 Notification reçue — Réservation {} pour le train {} (passager: {}, prix: {}€)",
                event.reservationId(),
                event.trainId(),
                event.passengerName(),
                event.price());

        // Simulation d'envoi de notification (email, SMS, push...)
        log.info("📧 Email envoyé (simulé) à {} pour la réservation {}",
                event.passengerName(),
                event.reservationId());
    }

    /**
     * Consumer DLT (Dead Letter Topic) — traite les messages en échec.
     *
     * 🎯 Quand un message échoue N fois sur le topic principal,
     * le DefaultErrorHandler l'envoie ici au lieu de le retenter indéfiniment.
     * En prod : alerter l'équipe, stocker en BDD pour analyse, retry manuel.
     */
    @KafkaListener(topics = "train-reservations.DLT", groupId = "notification-group")
    public void handleDlt(String payload) {
        log.error("💀 Message reçu sur DLT (Dead Letter Topic) — intervention manuelle requise : {}", payload);
    }

    private ReservationEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReservationEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Impossible de désérialiser l'événement : " + payload, e);
        }
    }
}
