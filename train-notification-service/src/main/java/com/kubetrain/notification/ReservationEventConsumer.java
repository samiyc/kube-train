package com.kubetrain.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Kafka qui traite les événements de réservation.
 *
 * 🎯 Concepts clés Spring Kafka :
 *  - @KafkaListener : écoute un topic et appelle la méthode pour chaque message
 *  - groupId : identifie le consumer group (Kafka distribue les partitions entre consumers d'un même groupe)
 *
 * 🎯 Pourquoi StringDeserializer + ObjectMapper ?
 *  Spring Kafka 4.0.0 JsonDeserializer référence encore Jackson 2 (com.fasterxml),
 *  mais Spring Boot 4 utilise Jackson 3 (tools.jackson). On reçoit le JSON en String
 *  et on désérialise manuellement — c'est un pattern courant en production car il
 *  donne plus de contrôle sur la gestion d'erreur de désérialisation.
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

    @KafkaListener(topics = "train-reservations", groupId = "notification-group")
    public void handleReservation(String payload) {
        try {
            ReservationEvent event = objectMapper.readValue(payload, ReservationEvent.class);

            log.info("📬 Notification reçue — Réservation {} pour le train {} (passager: {}, prix: {}€)",
                    event.reservationId(),
                    event.trainId(),
                    event.passengerName(),
                    event.price());

            // Ici on simule l'envoi d'une notification (email, SMS, push...)
            log.info("📧 Email envoyé (simulé) à {} pour la réservation {}",
                    event.passengerName(),
                    event.reservationId());

        } catch (Exception e) {
            // En prod : envoyer vers DLT ou alerter
            log.error("❌ Impossible de désérialiser l'événement : {}", payload, e);
        }
    }
}
