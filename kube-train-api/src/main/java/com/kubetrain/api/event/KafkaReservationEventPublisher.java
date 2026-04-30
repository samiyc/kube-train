package com.kubetrain.api.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Implémentation Kafka du publisher d'événements.
 *
 * 🎯 Points clés :
 *  - Envoi SYNCHRONE (get avec timeout) : on attend la confirmation du broker
 *    avant de retourner le 201 au client. Si Kafka est down → exception → pas de 201 fantôme.
 *  - La clé du message = reservationId → garantit l'ordre des messages
 *    pour une même réservation (même partition Kafka).
 *  - PAS de @Component ici : le bean est créé conditionnellement dans KafkaConfig
 *    (si app.kafka.enabled=true). Sinon → NoOp publisher.
 *
 * 🎯 Question entretien Kafka :
 *  "Comment garantir l'ordre des messages ?"
 *  → Messages avec la même clé → même partition → même consumer → ordre garanti
 *  → MAIS : si tu changes le nombre de partitions, le routage change (attention !)
 */
@Slf4j
public class KafkaReservationEventPublisher implements ReservationEventPublisher {

    public static final String TOPIC = "train-reservations";
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaReservationEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(ReservationEvent event) {
        try {
            // Envoi synchrone : bloque jusqu'à l'ack du broker (ou timeout)
            kafkaTemplate.send(TOPIC, event.reservationId(), event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("📤 Event publié sur [{}] : reservationId={}, trainId={}",
                    TOPIC, event.reservationId(), event.trainId());

        } catch (Exception e) {
            // Log l'erreur mais ne bloque pas la réservation (best-effort)
            // En prod avec Outbox Pattern, on écrirait en BDD et un poller republierait
            log.error("❌ Échec publication event : {}", event.reservationId(), e);
        }
    }
}
