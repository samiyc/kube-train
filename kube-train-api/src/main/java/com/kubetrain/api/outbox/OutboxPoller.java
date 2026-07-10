package com.kubetrain.api.outbox;

import com.kubetrain.api.entity.OutboxEvent;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.event.TracePropagation;
import com.kubetrain.api.repository.OutboxEventRepository;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * 🎯 Continuité de la trace :
 *  Ce scheduler s'exécute dans SA PROPRE trace, distincte de la requête HTTP qui a créé
 *  la ligne. Publier tel quel rattacherait notification-service à la trace du poller.
 *  On restaure donc le contexte W3C persisté en base (colonnes traceparent/tracestate,
 *  migration V4) le temps de la publication → la trace reste continue depuis
 *  POST /reservations jusqu'à l'envoi de l'email.
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

            // Cette méthode tourne dans la trace du @Scheduled, pas dans celle de la requête HTTP
            // qui a créé la ligne. On restaure le contexte figé en base le temps de publier : le
            // publisher injectera alors le traceparent d'ORIGINE dans le message, et le consumer
            // rattachera son span à la trace du POST /reservations.
            Context parent = TracePropagation.extract(traceAttributesOf(outboxEvent));
            try (Scope ignored = parent.makeCurrent()) {
                eventPublisher.publish(event);
            }

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

    /**
     * Attributs W3C persistés avec l'événement. Une map vide (lignes antérieures à la migration V4,
     * ou absence d'agent OTel) fait retomber {@code extract} sur le contexte courant : le
     * comportement reste celui d'avant, sans lien de trace.
     */
    private static Map<String, String> traceAttributesOf(OutboxEvent outboxEvent) {
        Map<String, String> attributes = new HashMap<>();
        if (outboxEvent.getTraceparent() != null) {
            attributes.put("traceparent", outboxEvent.getTraceparent());
        }
        if (outboxEvent.getTracestate() != null) {
            attributes.put("tracestate", outboxEvent.getTracestate());
        }
        return attributes;
    }
}
