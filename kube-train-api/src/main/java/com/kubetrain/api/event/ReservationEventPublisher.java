package com.kubetrain.api.event;

/**
 * Abstraction pour la publication d'événements de réservation.
 *
 * 🎯 Pourquoi une interface ?
 *  - Découple le service métier (TrainService) de l'infrastructure (Kafka)
 *  - Permet de MOCKER dans les tests unitaires (pas besoin de Kafka pour tester)
 *  - Respecte le principe d'inversion de dépendance (le D de SOLID)
 *  - Si on migre de Kafka vers Pub/Sub demain, on change juste l'implémentation
 *
 * 🎯 Question entretien SOLID :
 *  "Pourquoi ne pas injecter KafkaTemplate directement dans le service ?"
 *  → Couplage fort à l'infra, tests impossibles sans broker, violation du SRP
 */
public interface ReservationEventPublisher {

    void publish(ReservationEvent event);
}
