package com.kubetrain.api.outbox;

import com.kubetrain.api.entity.OutboxEvent;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler qui poll la table outbox_events et publie les événements en attente.
 *
 * 🎯 Pourquoi @Profile("postgres") ?
 *  Ce composant ne s'instancie que sur GKE (profil postgres + gcp).
 *  En local (profil default), il n'y a pas de table outbox → pas de poller.
 *
 * 🎯 Garantie at-least-once :
 *  On publie l'événement SUR Kafka/Pub Sub, PUIS on marque comme PROCESSED.
 *  Si le pod crashe entre les deux → l'événement sera republié (doublon).
 *  Le consumer (train-notification-service) est idempotent via ConcurrentHashMap → OK.
 *
 * 🎯 Retry automatique :
 *  En cas d'échec de publication, l'événement reste PENDING et sera retraité
 *  au prochain cycle (fixedDelay = 5 secondes).
 */
@Slf4j
@Component
@Profile("postgres")
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final ReservationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        ReservationEventPublisher eventPublisher,
                        ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Tourne toutes les 5 secondes (configurable via app.outbox.poll-interval-ms).
     * Lit les événements PENDING et les publie un par un.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    @Transactional
    public void processPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) return;
        log.debug("[OUTBOX] {} événement(s) en attente de publication", pending.size());
        pending.forEach(this::processEvent);
    }

    private void processEvent(OutboxEvent outboxEvent) {
        try {
            ReservationEvent event = objectMapper.readValue(outboxEvent.getPayload(), ReservationEvent.class);
            eventPublisher.publish(event);
            outboxEvent.setStatus("PROCESSED");
            outboxEvent.setProcessedAt(Instant.now());
            outboxEventRepository.save(outboxEvent);
            log.info("[OUTBOX] Événement {} traité — reservationId={}", outboxEvent.getId(), outboxEvent.getAggregateId());
        } catch (Exception e) {
            // Reste PENDING → retry au prochain cycle
            log.error("[OUTBOX] Échec publication événement {} (reservationId={}) : {}",
                    outboxEvent.getId(), outboxEvent.getAggregateId(), e.getMessage());
        }
    }
}
