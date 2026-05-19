# Formation 3 — Cloud Native Beyond — Notes de révision

## J1 — Flyway, Outbox Pattern & Spring Cloud Contract

### Flyway — Migrations versionnées

**Principe** : Flyway applique des scripts SQL versionnés (`V1__`, `V2__`...) dans l'ordre, une seule fois par environnement. L'historique est stocké dans `flyway_schema_history`.

**Règle d'or** : Ne JAMAIS modifier un fichier `Vn__` déjà appliqué → créer un `V(n+1)__` pour toute correction.

```
V1__create_reservations.sql  → table reservations
V2__grant_privileges.sql     → droits PostgreSQL 15 pour kube_train_user
V3__outbox_table.sql         → table outbox_events (Outbox Pattern)
```

**Config Spring Boot** (via `application.properties`) :
```properties
spring.flyway.enabled=true           # activé par défaut quand flyway-core est dans le classpath
spring.flyway.locations=classpath:db/migration
```

---

### Transactional Outbox Pattern

**Problème** : Sans outbox, si Kafka/Pub Sub est temporairement indisponible AU MOMENT de la réservation, l'événement est perdu. La réservation est en base mais le consumer notification ne reçoit jamais rien.

**Solution** : Dans la MÊME transaction SQL que la réservation, on écrit l'événement dans une table `outbox_events` (status=PENDING). Un scheduler (`OutboxPoller`) lit les événements en attente et les publie.

```
POST /reservations
    │
    ├── @Transactional
    │   ├── reservations.save(...)     → table reservations
    │   └── outbox_events.save(...)    → table outbox_events (status=PENDING)
    │
    └── Réponse 201 immédiate (avant publication Kafka)

OutboxPoller (@Scheduled, toutes les 5s)
    │
    ├── findByStatus("PENDING")
    ├── eventPublisher.publish(deserialize(payload))
    └── outbox_events.setStatus("PROCESSED")
```

**Garantie at-least-once** : on publie PUIS on marque PROCESSED. Si le pod crashe entre les deux, l'événement sera republié au redémarrage → doublon possible. Le consumer doit être idempotent (notre `ConcurrentHashMap` y veille).

**Fichiers concernés** :
- `V3__outbox_table.sql` — DDL de la table
- `OutboxEvent.java` — entité JPA (`@Profile("postgres")`)
- `OutboxEventRepository.java` — `findByStatusOrderByCreatedAtAsc("PENDING")`
- `OutboxPoller.java` — `@Scheduled` + `@Transactional` + `@Profile("postgres")`
- `TrainService.java` — `@Transactional createReservation()` → écrit dans l'outbox si disponible

---

### Spring Cloud Contract — Côté Producer

**Concept** : Les contrats YAML dans `src/test/resources/contracts/` définissent le comportement attendu de l'API. Le plugin Maven génère automatiquement des tests JUnit qui vérifient que le producer respecte ces contrats.

**Deux couches du contrat** :
- `body:` → valeurs d'exemple retournées par le **stub WireMock** (pour les consumers)
- `matchers:` → patterns regex vérifiés par les **tests auto-générés** du producer (valeur réelle)

```yaml
response:
  body:
    reservationId: "RES-EFB30294"    # valeur exemple → retournée par le stub WireMock
  matchers:
    body:
      - path: $.reservationId
        type: by_regex
        value: 'RES-[A-Z0-9]{8}'    # pattern → vérifié sur la vraie réponse API
```

**Sans `body:` pour un champ** → le stub WireMock ne retourne PAS ce champ dans la réponse.

**Pipeline de génération** :
```
mvn install
  → génère les tests JUnit à partir des contrats YAML
  → les tests vérifient que l'API retourne les bonnes valeurs/formats
  → génère le stubs JAR (kube-train-api-0.0.1-SNAPSHOT-stubs.jar)
  → installe le JAR dans ~/.m2 → disponible pour les consumers
```

---

### Spring Cloud Contract — Côté Consumer

**Concept** : Le consumer (`train-notification-service`) utilise le stubs JAR généré par le producer pour démarrer un serveur WireMock local. Les tests consumer vérifient que le consumer peut appeler la "vraie" API selon les contrats, sans démarrer le vrai service.

**Dépendance** :
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-contract-stub-runner</artifactId>
    <scope>test</scope>
</dependency>
```

**Annotation** :
```java
@AutoConfigureStubRunner(
    ids = "com.kubetrain:kube-train-api:0.0.1-SNAPSHOT:stubs:8181",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL  // cherche dans ~/.m2
)
```

**Pré-requis CI** : `mvn install` (producer) doit s'exécuter AVANT `mvn test` (consumer).

**Valeur** :
- Garantit la compatibilité inter-services sans démarrer l'API réelle
- Si le producer change son API de manière incompatible → le test producer casse → fail CI avant tout déploiement
- Les équipes peuvent travailler en parallèle (consumer travaille avec les stubs, pas l'API en prod)

---

---

### Validation E2E sur GCP — Trace complète (19/05/2026)

Exemple concret issu des logs Cloud Logging après un `POST /reservations` sur GKE Autopilot.

#### Timeline

```
09:39:58.094Z  [http-nio-8080-exec-5]  TrainService
               → Réservation RES-997D2CB0 persistée en Cloud SQL
               → outbox_events inséré : status=PENDING         ← même transaction SQL

09:39:58.277Z  [http-nio-8080-exec-5]  TrainController
               → "Réservation créée — id=RES-997D2CB0, wagon=Wagon 4"
               → 201 retourné au client                        ← ~180ms après le POST
                                                                  Pub/Sub pas encore touché !
               ┄┄┄┄┄ thread HTTP libéré ┄┄┄┄┄

09:40:03.921Z  [scheduling-1]          PubSubReservationEventPublisher
               → "[PUBSUB-PUBLISHER] Event publié — messageId=19111267208315942"

09:40:03.923Z  [scheduling-1]          OutboxPoller
               → "[OUTBOX] Événement 5e567f47... traité — reservationId=RES-997D2CB0"
               → outbox_events mis à jour : status=PROCESSED   ← +5.6s après HTTP response

09:40:06.786Z  [Gax-1]                 PubSubReservationEventConsumer (notification-service)
               → "Notification reçue — Réservation RES-997D2CB0 pour TGV-7042"
```

#### État de la table `outbox_events` (Cloud SQL Studio)

Juste après le POST (`09:39:58`) :
```json
{
  "id": "5e567f47-7912-47a3-9611-3562d405ba8b",
  "aggregate_id": "RES-997D2CB0",
  "event_type": "ReservationCreated",
  "status": "PENDING",
  "processed_at": "",
  "payload": "{\"eventId\":\"f4163f95-...\",\"reservationId\":\"RES-997D2CB0\",\"trainId\":\"TGV-7042\",\"passengerName\":\"Jean Dupont\",\"price\":29.90}"
}
```

5 secondes plus tard (`09:40:03`) :
```json
{
  "status": "PROCESSED",
  "processed_at": "2026-05-19T09:40:03.922384Z"
}
```

#### Ce que cette trace prouve

| Observation | Signification |
|---|---|
| Thread HTTP (`exec-5`) ≠ thread scheduler (`scheduling-1`) | Découplage total — le client ne subit pas la latence Pub/Sub |
| 201 retourné à `09:39:58.277Z`, Pub/Sub publié à `09:40:03.921Z` | +5.6s d'écart → le client n'attend pas la publication |
| Pub/Sub publié à `09:40:03.921Z`, PROCESSED à `09:40:03.923Z` | Ordre at-least-once respecté : publish **avant** marquage PROCESSED |
| Notification reçue à `09:40:06.786Z` | **8.5s** après la réponse HTTP, sans aucun impact côté client |
| `processed_at` = `09:40:03.922Z` soit `created_at + 5.8s` | Conforme au `fixedDelay=5000ms` de l'OutboxPoller (+ latence Cloud SQL) |

#### Payload JSON stocké en base

Le champ `payload TEXT` contient le `ReservationEvent` sérialisé — lisible directement depuis Cloud SQL Studio :
```json
{
  "eventId": "f4163f95-b95a-4640-a3bb-b039a75aeaf4",
  "reservationId": "RES-997D2CB0",
  "trainId": "TGV-7042",
  "passengerName": "Jean Dupont",
  "price": 29.90,
  "createdAt": "2026-05-19T09:39:57.930Z"
}
```

---

### Points clés entretien

| Question | Réponse |
|----------|---------|
| Différence Outbox vs publish direct | Outbox = atomicité SQL (réservation + événement dans la même TX). Publish direct = 2 opérations séparées, risque de perte si Kafka down |
| Pourquoi at-least-once et non at-most-once ? | On publie PUIS on marque PROCESSED → si crash entre les deux, on re-publie. Préférable à la perte de message. Nécessite idempotence côté consumer |
| body: vs matchers: dans les contrats | body = exemple concret pour le stub (valeur fixe) ; matchers = regex/pattern vérifié sur la vraie réponse du producer |
| Pourquoi @Profile("postgres") sur OutboxPoller ? | L'outbox nécessite JPA + DataSource. En local/tests sans profil postgres, il n'y a pas de table outbox → le poller ne s'instancie pas |
| Comment faire communiquer producer/consumer de contrats en CI ? | mvn install sur le producer génère le stubs JAR et l'installe en local. mvn test du consumer le consomme via StubRunner (StubsMode.LOCAL) |

---

## J2 — OpenTelemetry & Observabilité distribuée

### Pourquoi OpenTelemetry ?

Sans observabilité distribuée, impossible de répondre aux questions :
- *Pourquoi la requête POST /reservations prend 1.2s ?* (DB ? Pub/Sub ? réseau ?)
- *L'erreur vient de kube-train-api ou du notification-service ?*
- *Combien de réservations sont créées par minute ?*

**OpenTelemetry** (OTel) est le standard open-source pour collecter **traces**, **métriques** et **logs** de façon uniforme, vendor-agnostique.

Les trois pilliers de l'observabilité :

| Signal | Quoi | Exemple |
|---|---|---|
| **Traces** | Suivi d'une requête à travers plusieurs services | `POST /reservations` → SQL insert → outbox write |
| **Métriques** | Données numériques agrégées | `reservations.created = 42`, latence P95 = 320ms |
| **Logs** | Événements discrets horodatés | `[OUTBOX] Événement 5e567f47 traité` |

---

### OTel Java Agent — Instrumentation sans code

L'approche **agent** est un fichier JAR (`opentelemetry-javaagent.jar`) qui s'attache au processus JVM au démarrage via `-javaagent`. Il instrumente automatiquement :

- **HTTP** (Tomcat, Spring Web) → span pour chaque requête HTTP reçue ou émise
- **JDBC/JPA** → span pour chaque requête SQL (`SELECT`, `INSERT`, etc.)
- **Spring Scheduling** → span pour chaque tick du `@Scheduled` (OutboxPoller !)
- **Kafka** → span producer + consumer, propagation `traceparent` dans les headers
- **Micrometer** → exposition des métriques custom existantes (`reservations.created`)

**Avantage principal** : zéro modification du code applicatif.

```dockerfile
# Dans le Dockerfile — build stage
RUN wget -q -O /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.4.0/opentelemetry-javaagent.jar

# Run stage
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
```

**Variables d'environnement OTel clés** :
```bash
OTEL_SERVICE_NAME=kube-train-api           # nom du service dans les traces
OTEL_EXPORTER_OTLP_ENDPOINT=http://...:4317 # où envoyer les données
OTEL_EXPORTER_OTLP_PROTOCOL=grpc           # protocole de transport
OTEL_TRACES_EXPORTER=none                  # désactiver sans reconstruire l'image
```

---

### Architecture locale vs GKE

```
LOCAL (docker compose)                    GKE (production)
──────────────────────                    ────────────────
kube-train-api (mvnw)                     kube-train-api pod
  └─ pas d'agent → pas de traces           └─ -javaagent actif
                                              OTEL_EXPORTER_OTLP_ENDPOINT=
kube-train-api (docker image)                 http://otel-collector-service:4317
  └─ -javaagent actif                            │
     OTEL_EXPORTER_OTLP_ENDPOINT=                │ OTLP gRPC
     http://localhost:4317                       ▼
          │                               OTel Collector pod
          │ OTLP gRPC                     (otel/opentelemetry-collector-contrib)
          ▼                               ConfigMap : googlecloud exporter
     Jaeger                                      │
     (UI: localhost:16686)                       │ Cloud Trace API
                                                 ▼
                                          Cloud Trace (GCP Console)
```

---

### OTel Collector — rôle et configuration

Le **OTel Collector** est un proxy de télémétrie. Il reçoit des données OTLP (gRPC/HTTP), les traite, et les exporte vers une ou plusieurs destinations.

**Pourquoi un Collector intermédiaire plutôt qu'un export direct ?**
- Les apps n'ont pas besoin de connaître le backend (Cloud Trace, Jaeger, Datadog…)
- Authentification centralisée (ADC/Workload Identity sur le Collector, pas sur chaque pod)
- Buffer et retry en cas d'indisponibilité du backend
- Possibilité de router vers plusieurs backends simultanément

**Configuration du Collector (`k8s/otel-collector.yaml`)** :
```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317     # reçoit des pods

processors:
  memory_limiter:                  # protection OOM
    limit_percentage: 75
  batch:                           # regroupe les spans avant envoi
    timeout: 5s

exporters:
  googlecloud:                     # → Cloud Trace (via Workload Identity/ADC)
    project: kube-train-project
  debug:                           # → logs du pod (pour diagnostic)
    verbosity: basic

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [googlecloud, debug]
```

**Prérequis GCP (une seule fois)** :
```bash
# Activer l'API Cloud Trace
gcloud services enable cloudtrace.googleapis.com --project=kube-train-project

# Vérifier / ajouter le rôle cloudtrace.agent sur le compute SA
gcloud projects add-iam-policy-binding kube-train-project \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/cloudtrace.agent"
```

---

### Trace distribuée — propagation W3C TraceContext

Quand le kube-train-api reçoit une requête HTTP, l'agent crée un **Trace** (identifiant global unique) et un **Span** (opération individuelle). Chaque Span a :
- un `traceId` (identifie la requête de bout en bout)
- un `spanId` (identifie l'opération)
- un `parentSpanId` (relie les spans entre eux)

**Standard W3C TraceContext** : header HTTP `traceparent`:
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
              version  traceId (128 bits)              spanId (64 bits) flags
```

L'agent injecte/lit ce header automatiquement sur les appels HTTP sortants et entrants.

**Pour Kafka** : l'agent injecte le `traceparent` dans les headers du message → le consumer recrée un span enfant → trace bout-en-bout visible dans Cloud Trace.

**Pour Pub/Sub** : la propagation automatique n'est pas incluse dans l'agent standard. Les spans kube-train-api et notification-service apparaissent séparément dans Cloud Trace (limitation connue de J2, à adresser en J5 si besoin).

---

### Metric custom `reservations.created`

La métrique custom était **déjà en place** avant J2 via Micrometer dans `TrainService.java` :

```java
meterRegistry.counter("reservations.created", "train_id", train.id()).increment();
```

L'agent OTel exporte automatiquement les métriques Micrometer via OTLP. Dans Cloud Monitoring (GCP), la métrique apparaît sous `custom.googleapis.com/opencensus/reservations.created`.

Pour voir les métriques en local via Actuator :
```bash
curl http://localhost:8080/actuator/metrics/reservations.created
```

---

### Traces visibles dans Cloud Trace

Après déploiement, chaque `POST /reservations` génère une trace avec :

```
▼ POST /reservations [kube-train-api]                 ~300ms
  ├── SELECT kube_train.trains [JDBC]                  ~2ms
  ├── INSERT kube_train.reservations [JDBC]            ~5ms
  ├── INSERT kube_train.outbox_events [JDBC]           ~3ms
  └── scheduling-1 [OutboxPoller - 5s plus tard]
        ├── SELECT kube_train.outbox_events [JDBC]      ~2ms
        ├── Pub/Sub publish [PubSubPublisher]           ~50ms
        └── UPDATE kube_train.outbox_events [JDBC]      ~3ms
```

Navigation : GCP Console → Cloud Trace → Liste des traces → filtre `kube-train-api`

---

### Validation E2E sur GCP — Flame graph réelle (19/05/2026)

Trace capturée dans Cloud Trace après un `POST /reservations` sur GKE Autopilot.
TraceId : `9809bf57dbb2d0441ce355c53b57016e` | Durée totale : **25,093ms** | 9 spans

#### Flame graph complète

```
POST /reservations [kube-train-api]                              25,093ms (total)
│
│ ← ~6s de gap = Cold start du Cloud SQL Auth Proxy
│   (db-f1-micro qui se réveille après période d'inactivité)
│
├── ReservationRepository.save                                    3,808ms
│   └── Session.merge com.kubetrain.api.entity.Reservation        3,311ms
│       └── SELECT kube_train.reservations                        2,155ms
│           ↑ Hibernate vérifie si l'entité existe avant d'écrire (merge = upsert)
│
├── OutboxEventRepository.save                                      745µs
│   └── Session.persist com.kubetrain.api.entity.OutboxEvent        274µs
│       ↑ persist = INSERT direct, pas de SELECT préalable
│
└── Transaction.commit                                               11ms
    ├── INSERT kube_train.reservations                             2,43ms
    └── INSERT kube_train.outbox_events                           2,277ms
        ↑ Les 2 INSERTs dans la MÊME transaction = atomicité Outbox Pattern ✅
```

#### 3 insights clés de cette trace

**Insight 1 — L'Outbox Pattern est littéralement visible**

Les spans `INSERT kube_train.reservations` et `INSERT kube_train.outbox_events` sont tous deux **enfants du même `Transaction.commit`**. C'est la preuve visuelle de la garantie d'atomicité : si le processus crash entre les deux INSERTs, Hibernate rollback les deux. Jamais de réservation sans outbox, jamais d'outbox sans réservation.

**Insight 2 — `Session.merge` vs `Session.persist` : 3,311ms vs 274µs**

| Opération | Durée | Pourquoi |
|---|---|---|
| `ReservationRepository.save` (merge) | 3,808ms | Hibernate fait un `SELECT` avant d'écrire (upsert) |
| `OutboxEventRepository.save` (persist) | 745µs | INSERT direct, pas de SELECT préalable |

En prod, si `ReservationRepository.save` est lent, c'est le SELECT de merge à investiguer en premier. Solutions possibles : utiliser `saveAndFlush` avec `@GeneratedValue` bien configuré, ou confirmer qu'`OutboxEvent` utilise bien `persist` (entité toujours nouvelle, jamais de merge).

**Insight 3 — Les 25 secondes : cold start db-f1-micro**

La requête totale prend 25s, dont ~6s de gap avant les opérations DB. C'est le Cloud SQL Auth Proxy qui établit sa **première connexion** après une période d'inactivité (l'instance `db-f1-micro` est la plus petite, très lente à établir les connexions initiales). Sur les requêtes suivantes, ce gap disparaît → ~300ms total.

En prod réelle, on configurerait un **connection pool warmup** ou une instance Cloud SQL de taille supérieure (`db-g1-small` minimum pour prod).

#### Ce qu'on voit dans le Collector

```
2026-05-19T14:22:19.533Z   TracesExporter   resource spans: 1, spans: 119
2026-05-19T14:22:24.533Z   TracesExporter   resource spans: 1, spans: 5
2026-05-19T14:22:29.534Z   TracesExporter   resource spans: 2, spans: 58
```

- `resource spans: 1` → un seul service envoie des spans (kube-train-api seul actif)
- `resource spans: 2` → les deux services envoient (kube-train-api + train-notification-service)
- `spans: 119` → pic au démarrage (tous les spans d'initialisation JVM + Spring flushed d'un coup)
- `spans: 5-15` → régime normal (health probes + outbox polling toutes les 5s)

---

### Points clés entretien

| Question | Réponse |
|---|---|
| Différence Agent OTel vs instrumentation SDK | Agent = zéro code, auto-instrument via JVM attach. SDK = code explicite, plus de contrôle mais plus de code à écrire |
| Pourquoi OTel Collector plutôt qu'export direct ? | Centralise l'auth, découple les apps du backend, permet multi-export et retry |
| W3C TraceContext : c'est quoi le traceparent ? | Header HTTP qui propage traceId + spanId entre services. Permet de reconstituer une trace distribuée même si les services tournent sur des pods différents |
| Kafka vs Pub/Sub et OTel | Kafka : propagation `traceparent` automatique (header message). Pub/Sub : propagation manuelle via attributs message (non fait en J2) |
| Comment désactiver OTel sans reconstruire l'image ? | `OTEL_TRACES_EXPORTER=none` (env var dans le manifest K8s → redémarrage pod) |
