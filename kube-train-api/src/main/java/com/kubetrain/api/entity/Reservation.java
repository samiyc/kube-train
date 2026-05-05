package com.kubetrain.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entité JPA — représente une réservation persistée en base de données.
 *
 * 🎯 Différence entité vs DTO :
 *  - Entité (@Entity) : mappe une table SQL, gérée par Hibernate (cycle de vie JPA)
 *  - DTO (record) : contrat HTTP, ne dépend pas de JPA
 *  On convertit explicitement l'un en l'autre dans TrainService.
 *
 * Actif uniquement avec le profil "postgres" (JPA exclu par défaut, voir application.properties).
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    @Id
    @Column(name = "reservation_id", length = 50)
    private String reservationId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "train_id", nullable = false, length = 50)
    private String trainId;

    @Column(length = 50)
    private String wagon;

    @Column(name = "departure_time")
    private Instant departureTime;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "passenger_name", length = 200)
    private String passengerName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
