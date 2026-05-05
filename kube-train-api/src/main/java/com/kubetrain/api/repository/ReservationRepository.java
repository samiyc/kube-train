package com.kubetrain.api.repository;

import com.kubetrain.api.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA pour les réservations.
 *
 * 🎯 JpaRepository<Reservation, String> fournit automatiquement :
 *   save(), findById(), findAll(), delete(), count()...
 *   Pas besoin d'écrire de SQL pour les opérations CRUD classiques.
 *
 * Actif uniquement avec le profil "postgres" (JpaRepositoriesAutoConfiguration
 * est exclu par défaut dans application.properties).
 */
public interface ReservationRepository extends JpaRepository<Reservation, String> {
}
