package com.kubetrain.api.repository;

import com.kubetrain.api.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository JPA pour la table outbox_events.
 * Utilisé par OutboxPoller pour lire les événements en attente de publication.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Retourne tous les événements PENDING triés par date de création (FIFO) */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);
}
