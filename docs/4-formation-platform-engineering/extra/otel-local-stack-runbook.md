# Runbook — Stack OTel local + vérif trace complète api → notification

> Objectif : **voir** la trace distribuée complète (HTTP → publish → consume → « envoi email »)
> et démontrer l'observabilité **sans GCP**. Répond aux points F5 « aller plus loin avec OTel » et
> « OTel sans GCP ». À exécuter dans **WSL** (`/mnt/c/DEVDIR/GITHUB/kube-train`).

## Contexte — ce qui a été implémenté

| Transport | Propagation du contexte de trace | Statut |
|-----------|----------------------------------|--------|
| **Kafka** (local) | Auto par l'agent OTel (`traceparent` dans les headers) | ✅ natif |
| **Pub/Sub** (GKE) | **Manuelle** : `traceparent` injecté/extrait des attributs du message | ✅ ajouté (F5) |

Code ajouté :
- `kube-train-api` → `event/TracePropagation.java` : **injecte** le `traceparent` W3C dans les attributs Pub/Sub (`PubSubReservationEventPublisher`).
- `train-notification-service` → `TracePropagation.java` : **extrait** le contexte + ouvre un span `notification process`, et un span enfant `notification send-email` (Kafka **et** Pub/Sub) → l'étape « jusqu'à l'envoi du mail » devient visible.
- Sans agent OTel (tests, Minikube) → `GlobalOpenTelemetry` no-op → aucun effet (dégradation propre). Les 39 tests restent verts.

---

## Démo A — Trace complète api → notification (Jaeger, sans GCP)

Chemin **Kafka** : l'agent propage déjà le contexte ; on visualise le résultat dans Jaeger.

### 1. Infra locale
```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train
docker compose up -d kafka postgres jaeger
```

### 2. Récupérer l'agent OTel (une fois, à la racine)
```bash
curl -L --fail -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar
```

### 3. Builder les jars
```bash
(cd kube-train-api && ./mvnw -q -DskipTests package)
(cd train-notification-service && ./mvnw -q -DskipTests package)
```

### 4. Lancer les 2 services avec l'agent → Jaeger

**Terminal 1 — API** (profil `postgres` + Kafka, agent → Jaeger) :
```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/kube-train-api
OTEL_SERVICE_NAME=kube-train-api \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
SPRING_PROFILES_ACTIVE=postgres \
KAFKA_ENABLED=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
java -javaagent:../opentelemetry-javaagent.jar -jar target/kube-train-api-0.0.1-SNAPSHOT.jar
```

**Terminal 2 — Notification** (Kafka, agent → Jaeger) :
```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/train-notification-service
OTEL_SERVICE_NAME=train-notification-service \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
java -javaagent:../opentelemetry-javaagent.jar -jar target/train-notification-service-0.0.1-SNAPSHOT.jar
```

### 5. Déclencher une réservation
```bash
curl -X POST http://localhost:8080/reservations \
  -H "Content-Type: application/json" \
  -d '{"passengerName":"Alice","trainId":"TGV-7042"}'
```

### 6. Observer la trace dans Jaeger
Ouvrir **http://localhost:16686** → *Service* = `kube-train-api` → *Find Traces*.
Une **seule** trace doit contenir des spans des **deux** services :
```
POST /reservations                    (kube-train-api,        SERVER)
└─ train-reservations publish         (kube-train-api,        PRODUCER)   ← traceparent injecté
   └─ train-reservations process      (train-notification,    CONSUMER)   ← même traceId
      └─ notification send-email       (train-notification,    INTERNAL)   ← « envoi du mail »
```
✅ Si les 2 services apparaissent dans la même trace avec le span `notification send-email`,
la propagation est validée.

---

## Démo B — Observabilité « sans GCP » : métriques via OTel Collector

Répond à : *« Spring Boot /actuator/prometheus → OTel Collector (prometheus receiver) → visualiser ailleurs que GCP ? »* → **oui**.

Avec les 2 apps toujours lancées (Démo A) :
```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train
docker compose -f docker-compose.observability.yml up -d
```

| Composant | URL | Rôle |
|-----------|-----|------|
| OTel Collector | http://localhost:8889/metrics | Métriques agrégées (scrape `/actuator/prometheus`) |
| Prometheus | http://localhost:9090/targets | Doit montrer `otel-collector` = UP |
| Grafana | http://localhost:3000 (anonyme) | *Explore* → Prometheus |

Requêtes Grafana/Prometheus à tester :
```promql
notifications_processed_total
http_server_requests_seconds_count{job="kube-train-api"}
```
Chaîne complète **hors GCP** : `app /actuator/prometheus → Collector (:8889) → Prometheus → Grafana`.

Teardown :
```bash
docker compose -f docker-compose.observability.yml down
docker compose down
```

> 💡 Détail conceptuel (agent vs SDK, mono-service, backends alternatifs) : voir
> `extra/otel-sans-gcp.md`.

---

## Démo C — (optionnel, coût GCP) Vérifier la propagation Pub/Sub sur Cloud Trace

Le chemin **Pub/Sub** (profil `gcp`) ne tourne que sur GKE. À faire tant que l'essai GCP est actif.

1. Déployer (pipeline CI/CD ou manuel) — voir `extra/terraform-e2e-rebuild-runbook.md`.
2. Générer une réservation sur l'Ingress :
   ```bash
   IP=$(kubectl get svc -n ingress-nginx ingress-nginx-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
   curl -X POST https://api.$IP.nip.io/reservations -k \
     -H "Content-Type: application/json" \
     -d '{"passengerName":"Bob","trainId":"TGV-7042"}'
   ```
3. Console GCP → **Cloud Trace** → *Trace list* → ouvrir la trace :
   la trace doit lier `kube-train-api` **et** `train-notification-service` (span `notification process` + `notification send-email`).
   Avant le fix F5, les deux services apparaissaient dans **deux traces séparées**.
4. **Budget** : `terraform destroy` en fin de session.

---

## Pièges

- **Agent absent** = pas de trace (normal). Vérifier le flag `-javaagent` dans la commande `java`.
- **Jaeger occupe déjà `:4317`** sur l'hôte → le Collector local (Démo B) n'écoute **pas** en OTLP, uniquement le `prometheus receiver`. Les traces vont directement à Jaeger.
- **`host.docker.internal`** : sur Docker Linux (WSL sans Docker Desktop), le `extra_hosts: host-gateway` du compose est requis (déjà présent).
- **Pas de DB** : lancer l'API sans profil `postgres` désactive la persistance → `POST /reservations` échoue. Toujours démarrer `postgres` + `SPRING_PROFILES_ACTIVE=postgres`.
