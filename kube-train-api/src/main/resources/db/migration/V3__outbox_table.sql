-- Migration Flyway V3 : Transactional Outbox Pattern
--
-- 🎯 Pourquoi l'Outbox Pattern ?
--   Sans outbox : si Kafka/Pub Sub est temporairement down AU MOMENT de la réservation,
--   l'événement est perdu (la réservation est sauvée, mais le consumer ne reçoit rien).
--
--   Avec outbox : on écrit la réservation ET l'événement dans LA MÊME transaction SQL.
--   Un scheduler (OutboxPoller) relit la table et publie les événements en attente.
--   → Garantie "at-least-once" delivery sans XA/2-phase commit.
--
-- Cycle de vie d'un enregistrement :
--   PENDING   → créé dans la même tx que la réservation
--   PROCESSED → publié avec succès sur Kafka/Pub Sub
--   FAILED    → échec de publication (log d'erreur, à monitorer)

CREATE TABLE outbox_events (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(50)     NOT NULL,       -- reservationId
    event_type      VARCHAR(100)    NOT NULL,       -- ex : "ReservationCreated"
    payload         TEXT            NOT NULL,       -- JSON sérialisé du ReservationEvent
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    PRIMARY KEY (id)
);

-- Index sur status pour que le poller lise rapidement les événements en attente
CREATE INDEX idx_outbox_events_status ON outbox_events(status, created_at);
