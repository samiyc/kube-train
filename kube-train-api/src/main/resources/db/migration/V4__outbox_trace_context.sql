-- Migration Flyway V4 : propagation du contexte de trace à travers l'outbox
--
-- 🎯 Le problème résolu
--   Le pattern Outbox découple l'écriture de la publication : la requête HTTP écrit
--   une ligne puis se termine, et c'est OutboxPoller (@Scheduled) qui publie ~5 s plus
--   tard, dans un thread — donc dans une TRACE DIFFÉRENTE.
--
--   Résultat avant ce correctif : la trace de POST /reservations s'arrêtait au commit,
--   et les spans de notification-service apparaissaient dans une trace séparée (celle
--   du poller). Le lien de causalité était invisible.
--
-- 🎯 La solution
--   Persister le contexte de trace W3C (traceparent/tracestate) DANS la ligne outbox.
--   Le poller le restaure avant de publier → le consumer rattache son span à la trace
--   d'origine. La base de données devient le véhicule de propagation.
--
-- Colonnes nullables : les lignes créées avant cette migration (et les environnements
-- sans agent OTel : tests, Minikube) restent valides — le poller dégrade proprement.

ALTER TABLE outbox_events
    ADD COLUMN traceparent VARCHAR(64),   -- W3C : "00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>" (55 car.)
    ADD COLUMN tracestate  TEXT;          -- W3C : optionnel, taille variable (vendor state)
