package com.kubetrain.api.service;

import com.kubetrain.api.dto.*;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.exception.TrainNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couche service : logique métier séparée du controller.
 *
 * 🎯 Pourquoi séparer Controller et Service ?
 *  - Le Controller gère le HTTP (status codes, headers, validation)
 *  - Le Service gère le MÉTIER (règles, calculs, accès données)
 *  - Testable unitairement sans démarrer Spring (pas besoin de MockMvc)
 *  - Réutilisable : un autre controller (ou un consumer Kafka) peut appeler le même service
 */
@Service
public class TrainService {

    private final ReservationEventPublisher eventPublisher;

    public TrainService(ReservationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // Simule une base de données en mémoire (remplacé par Postgres/JPA en vrai)
    private static final Map<String, TrainResponse> TRAINS = Map.of(
            "TGV-7042", new TrainResponse("TGV-7042", "Paris Gare du Nord", "Lille Europe",
                    new BigDecimal("29.90"), 142),
            "TER-2814", new TrainResponse("TER-2814", "Lyon Part-Dieu", "Grenoble",
                    new BigDecimal("15.50"), 89),
            "IC-6734", new TrainResponse("IC-6734", "Bordeaux Saint-Jean", "Toulouse Matabiau",
                    new BigDecimal("22.00"), 203)
    );

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

    public ReservationResponse createReservation(CreateReservationRequest request) {
        TrainResponse train = getTrainById(request.trainId());

        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ReservationResponse reservation = ReservationResponse.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .trainId(train.id())
                .wagon("Wagon " + (int) (Math.random() * 12 + 1))
                .departureTime(Instant.now().plus(2, ChronoUnit.HOURS))
                .price(train.price())
                .build();

        reservations.put(reservation.reservationId(), reservation);

        // Publier l'événement Kafka (sync — attend l'ack du broker)
        eventPublisher.publish(ReservationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .reservationId(reservationId)
                .trainId(train.id())
                .passengerName(request.passengerName())
                .price(train.price())
                .createdAt(Instant.now())
                .build());

        return reservation;
    }

    public ReservationResponse getReservation(String reservationId) {
        ReservationResponse reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new TrainNotFoundException(reservationId);
        }
        return reservation;
    }
}
