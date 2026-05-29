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

---

## J3 — ArgoCD & GitOps

### Le problème du Push-based deployment

**Avant ArgoCD (modèle Push)** : la CI fait `kubectl apply` directement sur le cluster.

```
Développeur → git push → GitHub Actions → kubectl apply → Cluster GKE
                                          ↑
                              La CI a les credentials du cluster
                              La CI POUSSE les changements
```

**Problèmes du modèle Push** :
- La CI a un accès `cluster-admin` (surface d'attaque)
- Si quelqu'un modifie un manifest via `kubectl edit` → drift silencieux (pas de source de vérité)
- Pas de rollback automatique
- L'état du cluster n'est visible que depuis le cluster lui-même

---

### ArgoCD — le modèle Pull (GitOps)

**Principe GitOps** : Git est la **seule source de vérité** pour l'état du cluster. Un opérateur (ArgoCD) tourne DANS le cluster, surveille le repo Git, et applique les différences.

```
Développeur → git push → GitHub Actions → Build image + commit tag → Git (manifests)
                                                                          │
                                                                     ArgoCD (dans le cluster)
                                                                          │ pull (surveille)
                                                                          ▼
                                                                     Cluster GKE
```

**Avantages** :
- **CI sans credentials kubectl** — la CI ne fait que build + push image + commit
- **Self-healing** — si quelqu'un fait un `kubectl edit`, ArgoCD revient à l'état Git
- **Audit complet** — tout changement est un commit Git (qui, quand, quoi)
- **Rollback = git revert** — pas besoin de re-déployer, ArgoCD sync l'ancien état

---

### Architecture ArgoCD dans kube-train

```
┌────────────────────────────────────────────────────────┐
│  GitHub Actions CI (ne touche PLUS au cluster)         │
│                                                        │
│  1. mvn test (les deux services)                       │
│  2. docker build + push → Artifact Registry            │
│  3. sed -i "image: ...:<sha>" → git commit [skip ci]  │
└────────────────┬───────────────────────────────────────┘
                 │ commit image tags
                 ▼
┌────────────────────────────────────────────────────────┐
│  GitHub repo samiyc/kube-train (branche main)          │
│  └── k8s/                                              │
│      ├── deployment-gke.yaml  (image tag = SHA réel)   │
│      ├── notification-deployment-gke.yaml              │
│      ├── configmap.yaml, service.yaml, hpa.yaml        │
│      └── otel-collector.yaml                           │
└────────────────┬───────────────────────────────────────┘
                 │ poll toutes les 3 min (ou webhook)
                 ▼
┌────────────────────────────────────────────────────────┐
│  ArgoCD (namespace argocd, DANS le cluster GKE)        │
│                                                        │
│  • Détecte le diff Git ↔ état réel du cluster          │
│  • Apply automatique (syncPolicy.automated)            │
│  • Self-heal si drift                                  │
│  • Prune si resource supprimée de Git                  │
└────────────────┬───────────────────────────────────────┘
                 │ kubectl apply (interne)
                 ▼
┌────────────────────────────────────────────────────────┐
│  Cluster GKE Autopilot                                 │
│  ├── kube-train-deployment (2/2 Running)               │
│  ├── notification-deployment (1/1 Running)             │
│  └── otel-collector (1/1 Running)                      │
└────────────────────────────────────────────────────────┘
```

---

### Composants ArgoCD

| Composant | Rôle |
|---|---|
| `argocd-server` | UI web + API REST (le dashboard) |
| `argocd-repo-server` | Clone le repo Git, génère les manifests (Helm/Kustomize/plain YAML) |
| `argocd-application-controller` | Compare l'état Git vs cluster, déclenche les syncs |
| `argocd-redis` | Cache interne |
| `argocd-dex-server` | SSO/OAuth (optionnel) |

⚠️ Sur GKE Autopilot, ArgoCD consomme ~3 pods supplémentaires. Supprimer après J3 pour économiser les crédits.

---

### Application manifest (`k8s/argocd/application.yaml`)

```yaml
spec:
  source:
    repoURL: https://github.com/samiyc/kube-train.git
    path: k8s
    directory:
      include: '{deployment-gke.yaml,notification-deployment-gke.yaml,...}'
  syncPolicy:
    automated:
      selfHeal: true   # Corrige les drifts kubectl edit
      prune: true      # Supprime les ressources absentes de Git
```

**`selfHeal`** : si un dev fait `kubectl scale deployment/kube-train-deployment --replicas=5`, ArgoCD revient à la valeur dans Git (1 replica). Git gagne TOUJOURS.

**`prune`** : si tu supprimes `otel-collector.yaml` de Git et push → ArgoCD supprime le Deployment OTel du cluster.

---

### Migration Push → Pull (stratégie progressive)

La migration est faite en 3 étapes (pas de big bang) :

| Étape | CI fait... | ArgoCD fait... |
|---|---|---|
| **1. Actuelle** (hybride) | Build + push + kubectl apply + commit tags | Observe (pas encore installé) |
| **2. ArgoCD actif** | Build + push + commit tags | Sync auto depuis Git |
| **3. CI nettoyée** | Build + push + commit tags (kubectl supprimé) | Sync auto (source de vérité unique) |

On est à l'étape 1 après ce commit. Demain → étape 2 (install ArgoCD) → valider → étape 3 (retirer kubectl).

---

### Éviter la boucle infinie CI

Problème : CI commit les tags → push → déclenche une nouvelle CI → commit → ...

**Solution** : `paths-ignore` dans le trigger du workflow :
```yaml
on:
  push:
    branches: [main]
    paths-ignore:
      - '**/deployment-gke.yaml'
      - '**/notification-deployment-gke.yaml'
```

Si le seul fichier modifié est un deployment YAML → la CI ne se déclenche PAS.

---

### Points clés entretien

| Question | Réponse |
|---|---|
| GitOps vs CI/CD classique ? | GitOps = Git est la source de vérité, opérateur pull-based dans le cluster. CI/CD classique = push-based, la CI a les credentials. |
| Self-heal ArgoCD : c'est quoi ? | Si l'état du cluster diverge de Git (ex: kubectl edit), ArgoCD revient automatiquement à l'état Git. |
| Comment rollback avec ArgoCD ? | `git revert <commit>` → push → ArgoCD sync l'ancien état. Pas besoin de re-build ni de credentials kubectl. |
| ArgoCD vs Flux ? | Les deux sont CNCF. ArgoCD = UI riche, Application CRD. Flux = plus léger, GitRepository/Kustomization CRDs. |
| Pourquoi `[skip ci]` dans le commit de tag ? | Évite la boucle infinie : CI commit → push → trigger CI → commit → ... |
| Que se passe-t-il si ArgoCD est down ? | Le cluster continue de tourner (rien ne change). Au redémarrage, ArgoCD resynchronise le diff accumulé. |

---

## J4 — Sécurité applicative & réseau

### OAuth2 Resource Server — Concepts

**Le triangle OAuth2 :**

```
┌──────────────────┐         ┌──────────────────────┐
│  Client          │         │  Authorization Server│
│  (Postman, Front)│────────►│  (Keycloak, Auth0)   │
│                  │◄────────│                      │
│  Obtient un JWT  │  token  │  Émet les JWT        │
└───────┬──────────┘         └──────────────────────┘
        │
        │ Authorization: Bearer <jwt>
        ▼
┌──────────────────┐
│  Resource Server │
│  (kube-train-api)│
│                  │
│  Valide le JWT   │
│  (signature JWKS)│
└──────────────────┘
```

**Rôles :**
- **Authorization Server** (Keycloak) : authentifie l'utilisateur, émet les tokens JWT
- **Resource Server** (notre API) : valide les tokens, protège les ressources
- **Client** (Postman, front-end) : obtient un token et l'envoie avec chaque requête

**JWT (JSON Web Token)** : token auto-contenu = `header.payload.signature`
- **header** : algorithme de signature (RS256)
- **payload** : claims (sub, exp, iss, roles, email...)
- **signature** : signée par la clé privée de Keycloak → vérifiable avec la clé publique (JWKS)

---

### Anatomie d'un JWT réel (kube-train + Keycloak)

Un JWT = 3 parties encodées en Base64url, séparées par des `.`

```
eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJSV0ZJ...  ← Header
.
eyJleHAiOjE3Nzk5NTE5NzIsImlhdCI6MTc3OTk1MTY3MiwianRpIjoiMT...  ← Payload
.
W5L6mfMGUlkibejRLgZWhLOFOIcaPhtutqTnR6ZPxlV7IVpFQ4Y3yy4V...   ← Signature
```

**1. Header** (algorithme + clé utilisée)

```json
{
  "alg": "RS256",  // Algorithme RSA + SHA-256 (asymétrique : clé privée signe, clé publique vérifie)
  "typ": "JWT",
  "kid": "RWFI1Fi4-APITeGQ37CZlmUtFkibMOs65xO642vs0Yg"  // ID de la clé publique → Spring va chercher dans JWKS
}
```

**2. Payload** (les "claims" — exemple réel kube-train)

```json
{
  "exp": 1779951972,                             // Expiration (Unix timestamp) — Spring rejette si dépassé
  "iat": 1779951672,                             // Issued At → exp - iat = 300s = 5 min de validité
  "jti": "11af516c-1cd1-44e6-9aa0-5263293fa608", // JWT ID unique (évite le rejeu)
  "iss": "http://localhost:8180/realms/kube-train", // Issuer — doit matcher issuer-uri dans application-secured.properties
  "sub": "d30a1728-ce77-4486-949b-73ce0deaea47", // Subject = UUID de l'utilisateur dans Keycloak
  "typ": "Bearer",
  "azp": "kube-train-api",                       // Authorized Party = le client qui a demandé le token
  "scope": "email profile",                      // Scopes accordés
  "preferred_username": "testuser",              // Nom d'utilisateur Keycloak
  "email": "testuser@kube-train.local",
  "name": "Test User"
}
```

**3. Signature** — non décodable (c'est un hash signé)

```
Signature = RSA_sign(
  Base64url(header) + "." + Base64url(payload),
  clé_privée_keycloak
)
```
Spring Security vérifie cette signature avec la **clé publique** récupérée automatiquement depuis :
`http://localhost:8180/realms/kube-train/protocol/openid-connect/certs` (JWKS endpoint)

> 🔑 **Point clé** : le payload est lisible par n'importe qui (base64 ≠ chiffrement). La sécurité repose **uniquement sur la signature**. Ne jamais mettre de secrets dans les claims JWT.

**Décoder le payload depuis PowerShell :**

```powershell
$payload = $token.Split('.')[1]
$padded  = $payload + ('=' * ((4 - $payload.Length % 4) % 4))  # padding Base64
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($padded)) | ConvertFrom-Json
```

**Ce que fait Spring Security à chaque requête :**

```
Authorization: Bearer <jwt>
       │
       ▼
Spring extrait le header → lit "kid"
       │
       ▼
Récupère la clé publique depuis JWKS (mise en cache)
       │
       ▼
Vérifie la signature RSA
       │
       ▼
Vérifie exp > now, iss == issuer-uri configuré
       │
       ▼
✅ Crée un Authentication dans le SecurityContext
   (accès au sub, email, roles via @AuthenticationPrincipal)
```

---

### Implémentation dans kube-train

**Architecture à profils :**

```
┌─────────────────────────────────────────────────────┐
│  spring-boot-starter-oauth2-resource-server         │
│                                                     │
│  Profil "secured" actif :                           │
│  → SecurityConfig.java                              │
│  → JWT validé via issuer-uri (Keycloak)             │
│  → GET /trains/** = public                          │
│  → POST /reservations = authentifié                 │
│  → GET /secure = authentifié                        │
│                                                     │
│  Profil "secured" inactif (défaut) :                │
│  → PermissiveSecurityConfig.java                    │
│  → Tout est permitAll (développement rapide)        │
│  → Headers OWASP quand même ajoutés                 │
└─────────────────────────────────────────────────────┘
```

**Pourquoi deux configs ?**
- En dev local sans Keycloak → tout fonctionne comme avant
- En test OAuth2 (docker-compose + Keycloak) → profil `secured` activé
- Sur GKE → peut activer `secured` quand un IdP est configuré

**Obtenir un token (Keycloak local) :**

```powershell
# PowerShell (Windows — recommandé si app lancée depuis IntelliJ)
$resp = Invoke-RestMethod -Method POST `
  -Uri "http://localhost:8180/realms/kube-train/protocol/openid-connect/token" `
  -Body "grant_type=password&client_id=kube-train-api&client_secret=kube-train-secret&username=testuser&password=test123"
$token = $resp.access_token

# GET /secure (JWT + API key)
Invoke-RestMethod -Uri "http://localhost:8080/secure" `
  -Headers @{"Authorization"="Bearer $token"; "X-API-KEY"="dev-key"}

# POST /reservations avec JWT
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/reservations" `
  -Headers @{"Authorization"="Bearer $token"; "Content-Type"="application/json"} `
  -Body '{"passengerName":"Jean Dupont","trainId":"TGV-7042"}'

# Décoder le payload JWT
$payload = $token.Split('.')[1]
$padded = $payload + ('=' * ((4 - $payload.Length % 4) % 4))
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($padded)) | ConvertFrom-Json
```

```bash
# WSL/bash (si port-forward désactivé — voir ⚠️ ci-dessous)
TOKEN=$(curl -s -X POST http://localhost:8180/realms/kube-train/protocol/openid-connect/token \
  -d "grant_type=password&client_id=kube-train-api&client_secret=kube-train-secret&username=testuser&password=test123" \
  | jq -r .access_token)
curl http://localhost:8080/secure -H "Authorization: Bearer $TOKEN" -H "X-API-KEY: dev-key"
```

> ⚠️ **Piège WSL + IntelliJ** : si `kubectl port-forward service/kube-train-service 8080:80` tourne dans WSL,
> `localhost:8080` depuis WSL pointe vers GKE, pas vers IntelliJ. Utiliser PowerShell dans ce cas.

---

### Network Policies — Zero Trust réseau

**Principe** : par défaut, tous les pods K8s peuvent communiquer entre eux (flat network). Les NetworkPolicies ajoutent de la micro-segmentation.

**Implémentation kube-train (3 fichiers) :**

```
┌─────────────────────────────────────────────────────────────┐
│  Namespace default                                          │
│                                                             │
│  NetworkPolicy: default-deny-ingress                        │
│  → Bloque TOUT traffic entrant vers TOUS les pods           │
│                                                             │
│  NetworkPolicy: allow-ingress-api                           │
│  → Autorise ingress vers kube-train-pod:8080                │
│    - depuis namespace ingress-nginx (traffic externe)       │
│    - depuis les pods du namespace (probes kubelet)           │
│                                                             │
│  NetworkPolicy: allow-ingress-otel-collector                │
│  → Autorise ingress vers otel-collector:4317,4318           │
│    - depuis tous les pods du namespace (traces OTLP)        │
│                                                             │
│  notification-deployment :                                  │
│  → Aucune policy "allow" = AUCUN ingress autorisé           │
│  → Normal : il ne reçoit PAS de traffic entrant             │
│    (il PULL depuis Pub/Sub, c'est du egress)                │
└─────────────────────────────────────────────────────────────┘
```

**Point subtil** : le notification-service n'a pas besoin de policy ingress car il est un **consumer** (il initie les connexions vers Pub/Sub, ce qui est du egress). Le egress reste ouvert (DNS, Cloud SQL, Pub/Sub, etc.).

---

### Trivy — Scan de vulnérabilités images Docker

**Problème** : une image Docker contient un OS (Debian/Ubuntu) + des libraries. Des CVE sont publiées chaque jour. Si on ne scanne pas, on déploie des vulnérabilités connues en production.

**Solution** : Trivy dans la CI, APRÈS le build et AVANT le deploy.

```yaml
# Dans .github/workflows/deploy.yml (job build)
- name: Trivy — Scan vulnérabilités image API
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: ${{ env.IMAGE }}:${{ github.sha }}
    format: 'table'
    exit-code: '1'          # Fait échouer le build si vulnérabilité trouvée
    severity: 'CRITICAL'    # Seulement les CRITICAL bloquent (HIGH = warning)
    ignore-unfixed: true    # Ignore les CVE sans correctif disponible
```

**Niveaux de sévérité** :
- CRITICAL : exploit actif, impact direct → **bloque le deploy**
- HIGH : sérieux mais plus difficile à exploiter → warning (ne bloque pas)
- MEDIUM/LOW : informatif

**En entreprise** : on ajoute aussi un scan périodique (schedule) car de nouvelles CVE peuvent affecter des images déjà déployées.

---

### OWASP Security Headers

Headers HTTP ajoutés par Spring Security (même en mode permissif) :

| Header | Valeur | Protection |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Empêche le navigateur de "deviner" le MIME type (XSS via SVG) |
| `X-Frame-Options` | `DENY` | Empêche l'inclusion dans un iframe (clickjacking) |
| `Cache-Control` | `no-store` | Pas de cache navigateur sur les réponses authentifiées |
| `Strict-Transport-Security` | `max-age=...` | Force HTTPS (HSTS) — ajouté automatiquement si HTTPS actif |

Spring Security ajoute ces headers par défaut. Notre config les active explicitement pour la documentation.

---

### Keycloak — Configuration locale

**Realm pré-configuré** (importé automatiquement par docker-compose) :

| Élément | Valeur |
|---|---|
| Realm | `kube-train` |
| Client (confidentiel) | `kube-train-api` / secret: `kube-train-secret` |
| Client (public, pour front) | `kube-train-front` |
| User test | `testuser` / `test123` (rôle: user) |
| User admin | `admin` / `admin123` (rôles: user, admin) |
| URL admin | http://localhost:8180 |
| Issuer URI | http://localhost:8180/realms/kube-train |

**Pourquoi port 8180 ?** — Notre API est sur 8080. Keycloak par défaut est aussi sur 8080. On mappe sur 8180 pour éviter le conflit.

---

### Points clés entretien

| Question | Réponse |
|---|---|
| Différence Resource Server vs Client ? | Resource Server = valide les JWT (notre API). Client = obtient les JWT (front-end, Postman). |
| Pourquoi JWT plutôt que session ? | Stateless : pas de session serveur, scale horizontal trivial, chaque requête porte son auth. |
| Comment l'API valide un JWT sans contacter Keycloak à chaque requête ? | Elle télécharge les clés publiques (JWKS) au démarrage et les cache. Vérification locale de la signature. |
| NetworkPolicy : deny all suffit-il ? | Non. Il faut aussi des "allow" explicites sinon même les probes K8s et le DNS sont bloqués. |
| Trivy bloque sur CRITICAL : et si c'est un faux positif ? | Fichier `.trivyignore` pour lister les CVE ignorées (avec justification en commentaire). |
| Headers OWASP : pourquoi en mode permissif aussi ? | Défense en profondeur. Les headers protègent le navigateur même si l'authentification n'est pas active. |

---

## J5 — Qualité : BDD, SonarCloud & Quality Gates

### Cucumber BDD — Behavior-Driven Development

**Principe** : Écrire les comportements attendus en langage naturel (Gherkin) avant le code.
Le fichier `.feature` est lisible par le PO, le QA et le dev — c'est la "spécification vivante".

```
.feature  →  Step Definitions (Java)  →  Controller / Service
(Gherkin)     (@Given/@When/@Then)        (code réel testé via MockMvc)
```

**Vocabulaire Gherkin :**

| Mot-clé | Rôle |
|---------|------|
| `Feature` | Fonctionnalité métier (fichier entier) |
| `Scenario` | Cas d'usage concret |
| `Given` | Précondition (état initial) |
| `When` | Action (ce que l'utilisateur fait) |
| `Then` | Vérification (résultat attendu) |
| `And` | Chaîne une étape du même type |
| `Scenario Outline` + `Examples` | Template paramétré (N cas de test en 1 scénario) |

**Setup Maven (Spring Boot 4) :**
```xml
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-java</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-spring</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite</artifactId>
    <scope>test</scope>
</dependency>
```

**Pièges rencontrés sur kube-train :**

| Piège | Cause | Fix |
|-------|-------|-----|
| `package does not exist` | Fichiers dans `src/main/` | Toujours `src/test/java/` |
| `@AutoConfigureMockMvc` non trouvé | SB4 change de package | `org.springframework.boot.webmvc.test.autoconfigure` |
| `NoTestsDiscovered` | `@SelectPackages` cherche des classes JUnit | `@SelectClasspathResource("features")` |
| `illegal character: '\ufeff'` | BOM UTF-8 Windows | `new UTF8Encoding($false)` en PowerShell |
| `@Autowired` faux positif IntelliJ | Classe sans `@Component` | `@SuppressWarnings("SpringJavaAutowiredMembersInspection")` — NE PAS mettre `@Component` (brise les autres tests via composant scan) |

**Pattern MockMvc dans les steps :**
```java
// État partagé entre les étapes d'un même scénario
private ResultActions resultActions;

// WHEN — stocke le résultat pour les assertions du THEN
@When("je reserve un billet pour {string} sur le train {string}")
public void jeReserveUnBillet(String name, String trainId) throws Exception {
    this.resultActions = mockMvc.perform(post("/reservations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"passengerName": "%s", "trainId": "%s"}""".formatted(name, trainId)));
}

// GIVEN — ne stocke PAS dans resultActions (précondition passive)
@Given("le train {string} existe avec des places disponibles")
public void leTrainExiste(String trainId) throws Exception {
    mockMvc.perform(get("/trains/{id}", trainId)).andExpect(status().isOk());
}
```

---

### SonarCloud — Analyse qualité continue

**C'est quoi SonarCloud ?**
Service cloud (SaaS) qui analyse le code source à chaque push et mesure :
- **Bugs** : erreurs potentielles à l'exécution
- **Vulnerabilities** : failles de sécurité (OWASP)
- **Code Smells** : mauvaises pratiques (complexité, duplication…)
- **Coverage** : % de lignes couvertes par les tests (via JaCoCo)
- **Duplications** : code copié/collé

```
GitHub push  →  CI (mvnw test)  →  JaCoCo génère jacoco.xml
                                →  mvnw sonar:sonar  →  SonarCloud
                                                       → Quality Gate PASS/FAIL
```

**Quality Gate** = ensemble de seuils qui bloquent le merge si non atteints.
Exemple : coverage > 60%, 0 bugs, 0 vulnérabilités critiques.

**Setup kube-train :**

1. Propriétés dans `pom.xml` :
```xml
<sonar.organization>samiyc</sonar.organization>
<sonar.projectKey>samiyc_kube-train</sonar.projectKey>
<sonar.host.url>https://sonarcloud.io</sonar.host.url>
<sonar.coverage.jacoco.xmlReportPaths>${project.basedir}/target/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>
```

2. JaCoCo dans `pom.xml` (génère le rapport de couverture) :
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

3. GitHub Actions (job `test`, après les tests) :
```yaml
- name: Analyser avec SonarCloud
  run: |
    if [ -z "$SONAR_TOKEN" ]; then
      echo "⚠️ SONAR_TOKEN non configuré — analyse ignorée"
      exit 0
    fi
    ./mvnw sonar:sonar --no-transfer-progress -Dsonar.token="$SONAR_TOKEN"
  working-directory: kube-train-api
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

4. Secret GitHub : `SONAR_TOKEN` → Settings → Secrets → Actions

**JaCoCo vs SonarCloud — rôles distincts :**

| Outil | Rôle | Output |
|-------|------|--------|
| JaCoCo | Mesure la couverture à l'exécution des tests | `target/site/jacoco/jacoco.xml` |
| SonarCloud | Lit le rapport JaCoCo + analyse le code | Dashboard web + Quality Gate |

JaCoCo = instrument de mesure. SonarCloud = tableau de bord d'interprétation.

**Lancer l'analyse en local :**
```bash
cd kube-train-api
./mvnw test sonar:sonar -Dsonar.token=<ton_token>
```

---

### Points clés entretien J5

| Question | Réponse |
|----------|---------|
| BDD vs TDD ? | TDD = test avant code (unitaire, dev-centric). BDD = comportement en langage naturel (collaboratif, lisible par le PO). BDD utilise Gherkin + Cucumber, TDD utilise JUnit directement. |
| `@Given` vs `@When` vs `@Then` en Java ? | Fonctionnellement identiques (alias). La convention : Given = setup state, When = action, Then = assertion. L'ordre dans le `.feature` est documentaire. |
| Pourquoi JaCoCo ET SonarCloud ? | JaCoCo mesure la couverture (bytes instrumentés à l'exécution). SonarCloud interprète + centralise + historise + applique des Quality Gates. Sonar sans JaCoCo = analyse statique sans coverage. |
| Quality Gate bloquant : bonne pratique ? | Oui en production. En formation : `continue-on-error` ou condition `if [ -z "$SONAR_TOKEN" ]` pour rendre l'étape optionnelle pendant le setup. |
| Cucumber `Scenario Outline` ? | Template paramétré. Chaque ligne du tableau `Examples` génère un scénario distinct. Évite la duplication de scénarios quasi-identiques. |
