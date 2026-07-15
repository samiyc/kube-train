package com.kubetrain.notification;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
 * 🎯 Idempotence via eventId métier (PAS messageId) :
 *  Clé de dédup = event.eventId(), identique au consumer Kafka.
 *  Le messageId Pub/Sub serait un mauvais choix : l'outbox (kube-train-api) republie
 *  le même événement au cycle de poll suivant s'il n'a pas été marqué PROCESSED, avec
 *  un NOUVEAU messageId à chaque fois → une dédup par messageId ne verrait rien.
 *  L'eventId, lui, est figé dans le payload à la création → stable à travers les
 *  republications outbox ET les re-livraisons transport. Effectively-once garanti.
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
    // Cache des eventId déjà traités (démo — en prod : Redis/BDD partagée entre pods).
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    private Subscriber subscriber;

    public PubSubReservationEventConsumer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void start() {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, SUBSCRIPTION_ID);

        MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
            // Rattache le traitement au trace émis par kube-train-api (traceparent dans les attributs)
            Context parent = TracePropagation.extract(message.getAttributesMap());
            Span span = TracePropagation.startProcessSpan(parent);
            try (Scope scope = span.makeCurrent()) {
                handleMessage(message);
                consumer.ack();
            } catch (Exception e) {
                span.recordException(e);
                log.error("[PUBSUB-CONSUMER] Erreur traitement message {} : {}", message.getMessageId(), e.getMessage());
                consumer.nack();  // → retry automatique → DLQ après max-delivery-attempts
            } finally {
                span.end();
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
        String payload = message.getData().toStringUtf8();
        ReservationEvent event = objectMapper.readValue(payload, ReservationEvent.class);

        // Idempotence sur l'eventId métier (stable), pas le messageId Pub/Sub (voir javadoc).
        // Déserialisation AVANT la dédup : la clé vit dans le payload.
        if (!processedEventIds.add(event.eventId())) {
            log.warn("[PUBSUB-CONSUMER] Événement déjà traité, ignoré : eventId={}, reservationId={}",
                    event.eventId(), event.reservationId());
            return;
        }

        log.info("[PUBSUB-CONSUMER] Notification reçue — Réservation {} pour le train {} (passager : {})",
                event.reservationId(), event.trainId(), event.passengerName());
        TracePropagation.sendEmailSpan(event.reservationId(), () ->
                log.info("[PUBSUB-CONSUMER] Email envoyé (simulé) à {} pour la réservation {}",
                        event.passengerName(), event.reservationId()));

        meterRegistry.counter("notifications.processed", "train_id", event.trainId()).increment();
    }
}
