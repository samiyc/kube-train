package com.kubetrain.api.service;

import com.kubetrain.api.dto.*;
import com.kubetrain.api.entity.OutboxEvent;
import com.kubetrain.api.entity.Reservation;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.exception.TrainNotFoundException;
import com.kubetrain.api.repository.OutboxEventRepository;
import com.kubetrain.api.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Couche service : logique métier séparée du controller.
 *
 * 🎯 Pourquoi séparer Controller et Service ?
 *  - Le Controller gère le HTTP (status codes, headers, validation)
 *  - Le Service gère le MÉTIER (règles, calculs, accès données)
 *  - Testable unitairement sans démarrer Spring (pas besoin de MockMvc)
 *  - Réutilisable : un autre controller (ou un consumer Kafka) peut appeler le même service
 *
 * 🎯 Stratégie de persistance optionnelle (profil Spring) :
 *  - Profil "default" (local/tests) : reservationRepository == null → stockage en mémoire + publish direct
 *  - Profil "postgres" (GKE) : reservationRepository + outboxEventRepository injectés
 *    → persist en Cloud SQL + écriture dans l'outbox (OutboxPoller publie ensuite)
 *  L'injection optionnelle via @Autowired(required = false) évite de démarrer une DataSource
 *  quand le profil "postgres" n'est pas actif.
 */
@Slf4j
@Service
public class TrainService {

    private final ReservationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    // @Autowired(required=false) : injection optionnelle — null si profil "postgres" inactif.
    // Sonar java:S6813 (field injection) est supprimé ici car ce pattern est la seule façon
    // d'injecter des beans Spring conditionnels sans @Conditional sur le service lui-même.
    @SuppressWarnings("java:S6813")
    @Autowired(required = false)
    private ReservationRepository reservationRepository;

    @SuppressWarnings("java:S6813")
    @Autowired(required = false)
    private OutboxEventRepository outboxEventRepository;

    public TrainService(ReservationEventPublisher eventPublisher, MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    // Simule une base de données en mémoire pour les trains (pas de table trains en DB pour l'instant)
    private static final Map<String, TrainResponse> TRAINS = Map.of(
            "TGV-7042", new TrainResponse("TGV-7042", "Paris Gare du Nord", "Lille Europe",
                    new BigDecimal("29.90"), 142),
            "TER-2814", new TrainResponse("TER-2814", "Lyon Part-Dieu", "Grenoble",
                    new BigDecimal("15.50"), 89),
            "IC-6734", new TrainResponse("IC-6734", "Bordeaux Saint-Jean", "Toulouse Matabiau",
                    new BigDecimal("22.00"), 203)
    );

    // Stockage en mémoire pour le profil default (ignoré si reservationRepository est actif)
    private final ConcurrentHashMap<String, ReservationResponse> reservations = new ConcurrentHashMap<>();

    public List<TrainResponse> getAllTrains() {
        return List.copyOf(TRAINS.values());
    }

    public TrainResponse getTrainById(String trainId) {
        TrainResponse train = TRAINS.get(trainId);
        if (train == null) {
            throw new TrainNotFoundException(trainId);
        }
        return train;
    }

    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request) {
        TrainResponse train = getTrainById(request.trainId());
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant departureTime = Instant.now().plus(2, ChronoUnit.HOURS);
        String wagon = "Wagon " + (ThreadLocalRandom.current().nextInt(12) + 1);

        ReservationEvent event = buildEvent(reservationId, train, request.passengerName());
        ReservationResponse response = buildResponse(reservationId, train, wagon, departureTime);

        if (reservationRepository != null) {
            reservationRepository.save(toEntity(reservationId, train, request.passengerName(), wagon, departureTime));
            log.info("Réservation {} persistée en Cloud SQL (train={}, passager={})",
                    reservationId, train.id(), request.passengerName());
            enqueueEvent(event, reservationId);
        } else {
            reservations.put(reservationId, response);
            log.debug("Réservation {} stockée en mémoire (profil default)", reservationId);
            eventPublisher.publish(event);
        }

        meterRegistry.counter("reservations.created", "train_id", train.id()).increment();
        return response;
    }

    private ReservationEvent buildEvent(String reservationId, TrainResponse train, String passengerName) {
        return ReservationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .reservationId(reservationId)
                .trainId(train.id())
                .passengerName(passengerName)
                .price(train.price())
                .createdAt(Instant.now())
                .build();
    }

    private ReservationResponse buildResponse(String reservationId, TrainResponse train, String wagon, Instant departureTime) {
        return ReservationResponse.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .trainId(train.id())
                .wagon(wagon)
                .departureTime(departureTime)
                .price(train.price())
                .build();
    }

    private Reservation toEntity(String reservationId, TrainResponse train, String passengerName, String wagon, Instant departureTime) {
        return Reservation.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .trainId(train.id())
                .wagon(wagon)
                .departureTime(departureTime)
                .price(train.price())
                .passengerName(passengerName)
                .createdAt(Instant.now())
                .build();
    }

    /** Écrit dans l'outbox (profil postgres) ou publie directement (fallback). */
    private void enqueueEvent(ReservationEvent event, String reservationId) {
        if (outboxEventRepository == null) {
            eventPublisher.publish(event);
            return;
        }
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateId(reservationId)
                    .eventType("ReservationCreated")
                    .payload(objectMapper.writeValueAsString(event))
                    .status("PENDING")
                    .createdAt(Instant.now())
                    .build());
            log.debug("[OUTBOX] Événement {} enregistré pour la réservation {}", event.eventId(), reservationId);
        } catch (Exception e) {
            log.error("[OUTBOX] Impossible de sérialiser l'événement pour {} : {}", reservationId, e.getMessage());
            throw new RuntimeException("Échec écriture outbox pour " + reservationId, e);
        }
    }

    public ReservationResponse getReservation(String reservationId) {
        if (reservationRepository != null) {
            // Profil "postgres" : lecture en base de données
            return reservationRepository.findById(reservationId)
                    .map(entity -> ReservationResponse.builder()
                            .reservationId(entity.getReservationId())
                            .status(entity.getStatus())
                            .trainId(entity.getTrainId())
                            .wagon(entity.getWagon())
                            .departureTime(entity.getDepartureTime())
                            .price(entity.getPrice())
                            .build())
                    .orElseThrow(() -> new TrainNotFoundException(reservationId));
        }
        // Profil "default" : lecture en mémoire
        ReservationResponse reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new TrainNotFoundException(reservationId);
        }
        return reservation;
    }
}
