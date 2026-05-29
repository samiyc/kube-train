package com.kubetrain.api.event;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Publisher Pub/Sub — actif uniquement en profil "gcp" (GKE).
 *
 * 🎯 Pourquoi @Profile("gcp") et non @ConditionalOnProperty ?
 *  - Le profil "gcp" représente l'environnement entier (cloud managed)
 *  - Kafka reste disponible en local via docker-compose (profil non-gcp)
 *  - EventPublisherConfig crée le no-op si aucun publisher n'est présent
 *    (grace à @ConditionalOnMissingBean → pas besoin de modifier TrainService)
 *
 * 🎯 Authentification sur GKE :
 *  Application Default Credentials (ADC) — le pod GKE récupère automatiquement
 *  les credentials via le metadata server (Workload Identity ou compute SA).
 *  Aucune clé JSON à gérer.
 */
@Slf4j
@Component
@Profile("gcp")
public class PubSubReservationEventPublisher implements ReservationEventPublisher {

    private static final String TOPIC_ID = "train-reservations";

    @Value("${gcp.project-id}")
    private String projectId;

    private final ObjectMapper objectMapper;
    private Publisher publisher;

    public PubSubReservationEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws IOException {
        TopicName topicName = TopicName.of(projectId, TOPIC_ID);
        publisher = Publisher.newBuilder(topicName).build();
        log.info("[PUBSUB-PUBLISHER] Publisher initialisé sur topic [{}] (projet={})", TOPIC_ID, projectId);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        if (publisher != null) {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void publish(ReservationEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            ByteString data = ByteString.copyFromUtf8(json);
            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(data)
                    .putAttributes("reservationId", event.reservationId())
                    .build();

            ApiFuture<String> future = publisher.publish(message);
            String messageId = future.get(10, TimeUnit.SECONDS);
            log.info("[PUBSUB-PUBLISHER] Event publié — reservationId={}, messageId={}", event.reservationId(), messageId);
        } catch (InterruptedException e) {
            // Restaurer le flag d'interruption du thread (règle Sonar java:S2142)
            Thread.currentThread().interrupt();
            log.error("[PUBSUB-PUBLISHER] Thread interrompu lors de la publication — reservationId={}", event.reservationId(), e);
        } catch (Exception e) {
            log.error("[PUBSUB-PUBLISHER] Échec publication — reservationId={}", event.reservationId(), e);
        }
    }
}
