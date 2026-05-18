package com.kubetrain.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entité JPA — représente un événement en attente de publication (Outbox Pattern).
 *
 * 🎯 Transactional Outbox Pattern :
 *  1. Dans createReservation() : on sauvegarde la réservation ET cet événement
 *     dans la même transaction → atomicité garantie par SQL.
 *  2. OutboxPoller (scheduler) lit les événements PENDING et les publie sur Kafka/Pub Sub.
 *  3. En cas de succès → status = PROCESSED. En cas d'échec → reste PENDING (retry).
 *
 * Actif uniquement avec le profil "postgres" (JPA activé).
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** reservationId — permet de tracer l'événement jusqu'à la réservation source */
    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    /** Type d'événement métier (ex : "ReservationCreated") */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** JSON sérialisé du ReservationEvent — lu et publié par OutboxPoller */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** PENDING → PROCESSED (ou FAILED si erreur non récupérable) */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
