-- Migration Flyway V1 : création de la table des réservations
-- Flyway exécute ce fichier UNE SEULE FOIS et enregistre son checksum dans flyway_schema_history.
-- Pour modifier le schéma après coup, créer un V2__description.sql (jamais modifier un V existant).

CREATE TABLE reservations (
    reservation_id  VARCHAR(50)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    train_id        VARCHAR(50)     NOT NULL,
    wagon           VARCHAR(50),
    departure_time  TIMESTAMPTZ,
    price           NUMERIC(10, 2),
    passenger_name  VARCHAR(200),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (reservation_id)
);
