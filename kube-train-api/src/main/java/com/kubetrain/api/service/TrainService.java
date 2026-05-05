package com.kubetrain.api.service;

import com.kubetrain.api.dto.*;
import com.kubetrain.api.entity.Reservation;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.exception.TrainNotFoundException;
import com.kubetrain.api.repository.ReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * 🎯 Stratégie de persistance optionnelle (profil Spring) :
 *  - Profil "default" (local/tests) : reservationRepository == null → stockage en mémoire
 *  - Profil "postgres" (GKE) : reservationRepository injecté → persist en Cloud SQL
 *  L'injection optionnelle via @Autowired(required = false) évite de démarrer une DataSource
 *  quand le profil "postgres" n'est pas actif.
 */
@Service
public class TrainService {

    private final ReservationEventPublisher eventPublisher;

    // Injection optionnelle : null si profil "postgres" inactif (JPA exclu par défaut)
    @Autowired(required = false)
    private ReservationRepository reservationRepository;

    public TrainService(ReservationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
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

    public ReservationResponse createReservation(CreateReservationRequest request) {
        TrainResponse train = getTrainById(request.trainId());

        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant departureTime = Instant.now().plus(2, ChronoUnit.HOURS);
        String wagon = "Wagon " + (int) (Math.random() * 12 + 1);

        ReservationResponse response = ReservationResponse.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .trainId(train.id())
                .wagon(wagon)
                .departureTime(departureTime)
                .price(train.price())
                .build();

        if (reservationRepository != null) {
            // Profil "postgres" : persistance en base de données
            reservationRepository.save(Reservation.builder()
                    .reservationId(reservationId)
                    .status("CONFIRMED")
                    .trainId(train.id())
                    .wagon(wagon)
                    .departureTime(departureTime)
                    .price(train.price())
                    .passengerName(request.passengerName())
                    .createdAt(Instant.now())
                    .build());
        } else {
            // Profil "default" : stockage en mémoire (local, tests)
            reservations.put(reservationId, response);
        }

        // Publier l'événement Kafka (sync — attend l'ack du broker)
        eventPublisher.publish(ReservationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .reservationId(reservationId)
                .trainId(train.id())
                .passengerName(request.passengerName())
                .price(train.price())
                .createdAt(Instant.now())
                .build());

        return response;
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
