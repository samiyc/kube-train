package com.kubetrain.notification;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer Pub/Sub — actif uniquement en profil "gcp" (GKE).
 *
 * 🎯 Équivalent de ReservationEventConsumer (Kafka) mais pour Pub/Sub.
 *  Même logique métier, même idempotence, même counter Micrometer.
 *
 * 🎯 Idempotence via messageId Pub/Sub :
 *  Pub/Sub garantit "at-least-once" (comme Kafka).
 *  messageId est unique par message → on l'utilise comme clé d'idempotence
 *  (équivalent de l'eventId côté Kafka).
 *
 * 🎯 Ack / Nack :
 *  - consumer.ack() → message retiré de la subscription
 *  - consumer.nack() → re-livré jusqu'à max-delivery-attempts, puis DLQ
 *
 * 🎯 Authentification sur GKE :
 *  ADC (Application Default Credentials) — credentials automatiques via le
 *  metadata server GKE. Aucune configuration explicite nécessaire.
 */
@Slf4j
@Component
@Profile("gcp")
public class PubSubReservationEventConsumer {

    private static final String SUBSCRIPTION_ID = "notification-subscription";

    @Value("${gcp.project-id}")
    private String projectId;

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    private Subscriber subscriber;

    public PubSubReservationEventConsumer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void start() {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, SUBSCRIPTION_ID);

        MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
            try {
                handleMessage(message);
                consumer.ack();
            } catch (Exception e) {
                log.error("[PUBSUB-CONSUMER] Erreur traitement message {} : {}", message.getMessageId(), e.getMessage());
                consumer.nack();  // → retry automatique → DLQ après max-delivery-attempts
            }
        };

        subscriber = Subscriber.newBuilder(subscriptionName, receiver).build();
        subscriber.startAsync().awaitRunning();
        log.info("[PUBSUB-CONSUMER] Subscriber démarré sur [{}] (projet={})", SUBSCRIPTION_ID, projectId);
    }

    @PreDestroy
    void stop() {
        if (subscriber != null) {
            subscriber.stopAsync();
        }
    }

    private void handleMessage(PubsubMessage message) throws Exception {
        String messageId = message.getMessageId();

        // Idempotence — évite le double traitement en cas de re-livraison Pub/Sub
        if (!processedMessageIds.add(messageId)) {
            log.warn("[PUBSUB-CONSUMER] Message déjà traité, ignoré : {}", messageId);
            return;
        }

        String payload = message.getData().toStringUtf8();
        ReservationEvent event = objectMapper.readValue(payload, ReservationEvent.class);

        log.info("[PUBSUB-CONSUMER] Notification reçue — Réservation {} pour le train {} (passager : {})",
                event.reservationId(), event.trainId(), event.passengerName());
        log.info("[PUBSUB-CONSUMER] Email envoyé (simulé) à {} pour la réservation {}",
                event.passengerName(), event.reservationId());

        meterRegistry.counter("notifications.processed", "train_id", event.trainId()).increment();
    }
}
