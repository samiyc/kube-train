-- Migration Flyway V2 : droits d'accès pour kube_train_user
-- ⚠️  Ce script doit être exécuté par un superutilisateur (postgres).
--     En pratique : configurer spring.flyway.user=postgres dans l'environnement GKE,
--     ou exécuter manuellement une seule fois via Cloud Shell :
--       gcloud sql connect kube-train-db --user=postgres --database=kube_train
--
-- PostgreSQL 15+ : le schéma public n'est plus accessible par défaut aux nouveaux utilisateurs.
-- Ces GRANTs sont nécessaires pour que kube_train_user puisse :
--   - Se connecter à la base (CONNECT)
--   - Lire et écrire dans le schéma public (USAGE + CREATE)
--   - Manipuler les tables existantes et futures

GRANT CONNECT ON DATABASE kube_train TO kube_train_user;
GRANT USAGE, CREATE ON SCHEMA public TO kube_train_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO kube_train_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO kube_train_user;

-- Droits automatiques sur les futures tables créées par postgres
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO kube_train_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON SEQUENCES TO kube_train_user;
