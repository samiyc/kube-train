## 📝 J1 — Notes de révision : API-First, Swagger & Gestion des erreurs

### Architecture REST — Les bases (questions d'entretien Spring)

**Richardson Maturity Model** — 4 niveaux de maturité d'une API REST :

| Niveau | Description | Exemple |
|--------|-------------|---------|
| 0 | Un seul endpoint, XML/SOAP | `POST /api` pour tout |
| 1 | Ressources (URLs = noms) | `/trains`, `/reservations` |
| 2 | Verbes HTTP corrects | `GET /trains`, `POST /reservations` |
| 3 | HATEOAS (hypermedia links) | Liens dans les réponses pour naviguer l'API |

kube-train est au **niveau 2** — suffisant pour 95% des APIs en prod.

### Annotations Spring MVC essentielles

```java
@RestController          // = @Controller + @ResponseBody (JSON auto)
@RequestMapping("/api")  // Préfixe URL pour tous les endpoints de la classe
@GetMapping("/{id}")     // = @RequestMapping(method = GET) — GET, POST, PUT, DELETE, PATCH
@PathVariable            // Paramètre dans l'URL : /trains/{id}
@RequestParam            // Paramètre query string : /trains?page=2
@RequestBody             // Corps JSON → objet Java (désérialisé par Jackson)
@ResponseStatus(CREATED) // Code HTTP de retour (201, 204, etc.)
@Valid                   // Déclenche la validation Jakarta (javax.validation → jakarta.validation)
```

### Gestion des erreurs — Spring Boot 4 (ProblemDetail / RFC 9457)

**Hiérarchie d'exception Spring MVC** :
```
Exception
 └─ RuntimeException
     ├─ ResponseStatusException(HttpStatus, message)  ← rapide, jetable n'importe où
     └─ Custom exceptions (TrainNotFoundException, etc.)
         └─ Interceptées par @ControllerAdvice / @ExceptionHandler
```

**Pattern `@ControllerAdvice` + `ProblemDetail`** (standard depuis Spring Boot 3/4) :
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TrainNotFoundException.class)
    public ProblemDetail handleNotFound(TrainNotFoundException e) {
        ProblemDetail pb = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, e.getMessage());
        pb.setTitle("Ressource introuvable");     // Titre lisible
        pb.setProperty("errorCode", "TRAIN_001"); // Champ custom
        return pb;  // Spring renvoie 404 + JSON normalisé
    }
}
```

**Réponse JSON produite** (RFC 9457) :
```json
{
  "type": "about:blank",
  "title": "Ressource introuvable",
  "status": 404,
  "detail": "Train TGV-9999 non trouvé",
  "instance": "/trains/TGV-9999",
  "errorCode": "TRAIN_001"
}
```

**Piège entretien** : "Quelle est la différence entre `@ControllerAdvice` et `@RestControllerAdvice` ?"
→ `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody` (pas besoin de `ResponseEntity`).

**Codes HTTP à connaître par cœur** :

| Code | Signification | Quand l'utiliser |
|------|--------------|-----------------|
| 200 | OK | GET réussi |
| 201 | Created | POST qui crée une ressource |
| 204 | No Content | DELETE réussi (pas de body) |
| 400 | Bad Request | Validation échouée (`@Valid`) |
| 401 | Unauthorized | Pas de credentials / credentials invalides |
| 403 | Forbidden | Credentials OK mais pas le droit |
| 404 | Not Found | Ressource inexistante |
| 409 | Conflict | Doublon, violation d'unicité |
| 500 | Internal Server Error | Bug serveur (catch-all) |

### Swagger / OpenAPI — Annotations clés

```java
@Tag(name = "Trains")                                               // Groupe dans Swagger UI
@Operation(summary = "Liste les trains")                            // Description de l'endpoint
@ApiResponse(responseCode = "200", description = "OK")              // Documenter chaque code retour
@Schema(description = "Identifiant du train", example = "TGV-7042") // Sur les champs DTO
@Parameter(description = "ID du train", required = true)            // Sur les @PathVariable
```

**Config Swagger** : `OpenApiConfig.java` crée le bean `OpenAPI` avec titre, version, description.
URL d'accès : `/swagger-ui/index.html` (servie par `springdoc-openapi`).

### DTOs — Records Java (Java 16+)

```java
public record ReservationRequest(
    @NotBlank String trainId,       // Validation Jakarta
    @NotBlank String passengerName,
    @Positive double price          // > 0
) {}
```
**Pourquoi des records ?** Immutables, compacts, `equals/hashCode/toString` auto-générés.
**Lombok `@Builder`** : fonctionne sur les records depuis Lombok 1.18.30+.

### Contract Testing — Spring Cloud Contract

**Principe** : le producteur (API) et le consommateur s'accordent sur un contrat (YAML/Groovy).
Le plugin Maven génère automatiquement des tests JUnit qui vérifient le respect du contrat.

**Fichier contrat** (`src/test/resources/contracts/*.yml`) :
```yaml
request:
  method: GET
  url: /trains
response:
  status: 200
  body:
    - trainId: "TGV-7042"
  matchers:
    body:
      - path: $[0].trainId
        type: by_regex("[A-Z]+-\\d+")
```

**Mots-clés** : Consumer-Driven Contracts, Stubs WireMock, `BaseContractTest`, `@WebMvcTest`.

---

## 📝 J2 — Notes de révision : Kafka & Event-Driven Architecture

### Kafka — Concepts fondamentaux

```
┌─ Producer ──┐     ┌─ Kafka Broker ────────────────────────┐     ┌─ Consumer ──────┐
│ kube-train  │────>│  Topic: train-reservations            │────>│ notification    │
│ (API)       │     │    Partition 0: [msg1][msg2][msg3]... │     │ service         │
└─────────────┘     │    Partition 1: [msg4][msg5]...       │     │ (groupId: notif)│
                    └───────────────────────────────────────┘     └─────────────────┘
```

| Concept | Définition |
|---------|-----------|
| **Topic** | File de messages nommée (ex: `train-reservations`) |
| **Partition** | Sous-division d'un topic pour le parallélisme |
| **Offset** | Numéro séquentiel d'un message dans une partition |
| **Consumer Group** | Ensemble de consumers qui se partagent les partitions |
| **Broker** | Serveur Kafka (1 ou plusieurs en cluster) |
| **Rebalancing** | Redistribution des partitions quand un consumer rejoint/quitte le group |

**Règle d'or** : nombre de consumers ≤ nombre de partitions dans un group.
Au-delà → certains consumers restent idle (gaspillage).

### Annotations / Classes Spring Kafka

```java
// PRODUCER
KafkaTemplate<String, Object>        // Client d'envoi (injecté par Spring)
template.send("topic", key, value)   // Envoi async (renvoie CompletableFuture)
template.send(...).get(10, SECONDS)  // Envoi sync (attend l'ACK du broker)

// CONSUMER
@KafkaListener(topics = "train-reservations", groupId = "notification-group")
public void handle(String payload) { ... }

// CONFIG
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
// → Active/désactive tout Kafka via application.properties
```

### Sérialisation — Piège Jackson 2 vs Jackson 3

Spring Boot 4 utilise **Jackson 3** (`tools.jackson.databind.ObjectMapper`).
`spring-kafka` 4.0.0 `JsonDeserializer` référence encore **Jackson 2** (`com.fasterxml`).

**Solution** : `StringDeserializer` + désérialisation manuelle avec `ObjectMapper` Jackson 3.
C'est un pattern courant en prod (plus de contrôle sur les erreurs de désérialisation).

```properties
# application.properties du consumer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
```

### Pattern Idempotence (at-least-once → exactly-once sémantique)

**Problème** : Kafka garantit "at-least-once" → un message peut être reçu 2+ fois
(rebalancing, retry, crash avant commit offset).

**Solution** : stocker les `eventId` déjà traités.
```java
Set<String> processedIds = ConcurrentHashMap.newKeySet(); // Démo
if (!processedIds.add(event.eventId())) { return; }       // Doublon ignoré
```
**En prod** : table PostgreSQL `processed_events(event_id PK, processed_at)` ou Redis SET.

### Pattern Dead Letter Topic (DLT)

**Problème** : Un message "poison" (JSON invalide, erreur métier) est retenté indéfiniment
→ le consumer est bloqué, les messages s'accumulent.

**Solution** : Après N retries → envoyer au topic `-dlt` et continuer.
⚠️ Spring Kafka 4.x : le suffixe DLT est `-dlt` (minuscules, hyphen). Avant c'était `.DLT`.
```java
// KafkaErrorConfig.java
DefaultErrorHandler handler = new DefaultErrorHandler(
    new DeadLetterPublishingRecoverer(dltKafkaTemplate),
    new FixedBackOff(1000L, 2)  // 2 retries, 1s d'intervalle = 3 tentatives max
);
```
**Consumer DLT** : `@KafkaListener(topics = "train-reservations-dlt")` → alerting + stockage.

**En prod** :
- `ExponentialBackOff` au lieu de `FixedBackOff` (évite de surcharger un service down)
- Retry conditionnel : JSON invalide → direct DLT, timeout réseau → retry
- Monitoring/alerting si le topic DLT n'est pas vide

### Pattern Outbox (théorie)

**Problème** : `save(BDD)` + `publish(Kafka)` = 2 opérations NON atomiques.
```
❌ save(BDD) → publish(Kafka)  → si Kafka down → event perdu, BDD incohérente
❌ publish(Kafka) → save(BDD)  → si BDD down → event fantôme
```

**Solution** : écrire l'event dans une table `outbox` (MÊME transaction SQL) :
```sql
BEGIN;
INSERT INTO reservations (...) VALUES (...);
INSERT INTO outbox (event_type, payload) VALUES ('ReservationCreated', '{"id":"..."}');
COMMIT;
```
Un **poller** ou **Debezium (CDC — Change Data Capture)** lit la table outbox et publie sur Kafka.
→ Garantie : si c'est en BDD, c'est sur Kafka (eventually consistent).

### Pattern Saga (théorie)

**Problème** : Transaction distribuée entre N micro-services
(ex: Commande → Paiement → Stock → Livraison).

**Saga** = séquence de transactions locales liées par des événements.
Si une étape échoue → **transactions compensatoires** (annulation des étapes précédentes).

| Style | Mécanisme | Avantage |
|-------|-----------|----------|
| Choreography | Chaque service écoute et publie des events | Simple, découplé |
| Orchestration | Un orchestrateur (Saga Manager) pilote les étapes | Visible, traceable |

kube-train utilise la **choreography** : `KubeTrainApi` publie → `NotificationService` écoute.

**Orchestration** (chef d'orchestre) :
```
OrderSaga contrôle tout → appelle chaque service → rollback si échec
+ Facile à suivre / debugger
- Single point of failure
```

**Chorégraphie** (chaque service réagit aux events) :
```
OrderCreated → PaymentService écoute → PaymentDone → StockService écoute → ...
+ Découplé, scalable
- Flux difficile à suivre (distribué)
```
**Règle** : Chorégraphie pour 2-3 étapes, Orchestration au-delà.

### Kafka vs Pub/Sub vs RabbitMQ — Comparatif

| | Kafka | GCP Pub/Sub | RabbitMQ |
|--|-------|-------------|----------|
| **Modèle** | Log distribué (append-only) | Queue managée (serverless) | Message broker (AMQP) |
| **Rétention** | Configurable (jours/semaines) | 7j par défaut | Consommé = supprimé |
| **Replay** | ✅ Relire depuis un offset | ✅ Seek | ❌ |
| **Ordering** | Par partition (garanti) | Par clé d'ordering | Par queue |
| **Ops** | Auto-géré ou Confluent Cloud | Full managed (0 infra) | Auto-géré ou CloudAMQP |
| **Use case** | Event sourcing, streaming, big data | Cloud-native GCP, serverless | Task queue, RPC, routing |

### Docker Compose Kafka — KRaft (sans Zookeeper)

Depuis Kafka 3.3+ : KRaft remplace Zookeeper (1 seul process au lieu de 2).
```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092                      # ⚠️ Pas 0.0.0.0 (rejeté par Kafka)
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092  # URL que les clients utilisent
CLUSTER_ID: 'xxxxx'                                     # ID stable pour les redémarrages
```
**Voir les messages** : `kafka-console-consumer --from-beginning` (pas les logs du conteneur).
**Écrire des messages** : `docker exec -it` (avec `-it` pour le stdin interactif).

### Spring Boot 4 — Piège `spring-boot-starter-kafka`

Spring Boot 4 a modularisé l'auto-configuration. `spring-kafka` seul **ne suffit plus** :
- `spring-kafka` = la lib Spring Kafka
- `spring-boot-starter-kafka` = la lib + l'auto-configuration (`@KafkaListener` activé)

Sans le starter, les `@KafkaListener` sont **silencieusement ignorés** (aucun consumer créé).

### Activation conditionnelle — Pattern production

```java
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig { ... }   // Active si KAFKA_ENABLED=true

@Configuration
public class EventPublisherConfig {
    @Bean
    @ConditionalOnMissingBean(ReservationEventPublisher.class)
    public ReservationEventPublisher noOpPublisher() { ... }  // Fallback sans Kafka
}
```
→ L'API fonctionne **avec ou sans** Kafka. En dev local : `KAFKA_ENABLED=false` → NoOp.
En prod/docker : `KAFKA_ENABLED=true` → publication réelle.

---

## 📝 J3 — Notes de révision : GCP Services

### GCP Secret Manager

**Pourquoi Secret Manager plutôt que `kubectl create secret` ?**

| Critère | kubectl create secret | GCP Secret Manager |
|---------|----------------------|-------------------|
| Audit trail | Non | Oui (Cloud Audit Logs) |
| Versioning | Non | Oui (v1, v2, ...) |
| Rotation | Manuelle | Programmable |
| Scope | Limité au cluster | Multi-cluster, multi-service |
| Accès | Qui a accès au cluster | IAM granulaire par secret |

**Pattern CI/CD (approche retenue — sans Spring Cloud GCP)** :
```
GCP Secret Manager
  → GitHub Actions (gcloud secrets versions access latest)
  → kubectl create secret --dry-run=client -o yaml | kubectl apply   ← upsert idempotent
  → K8s Secret → secretKeyRef → env var TRAIN_API_KEY
  → Spring @Value("${train.api.key}")
```

**Commandes clés** :
```bash
# Créer le secret
echo -n "valeur" | gcloud secrets create mon-secret --data-file=- --project=PROJECT_ID

# Lire la dernière version
gcloud secrets versions access latest --secret=mon-secret --project=PROJECT_ID

# Donner l'accès à un SA
gcloud secrets add-iam-policy-binding mon-secret \
  --member="serviceAccount:SA@PROJECT.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

**Identifiants GCP** :
- `projectId` = nom textuel choisi à la création (`kube-train-project`)
- `projectNumber` = numéro auto-généré par Google, utilisé dans les SA names
- `gcloud projects describe PROJECT_ID --format='value(projectNumber)'`

**Spring Cloud GCP + Spring Boot 4** : la version 8.x (Boot 4 compatible) n'est pas encore disponible sur Maven Central (mai 2025). Le pattern CI/CD est la solution de contournement production-réaliste.

---

### HTTPS sur GKE — cert-manager + Let's Encrypt + nip.io

**Stack HTTPS** :
```
nip.io (DNS gratuit)     → résout api.X.X.X.X.nip.io → IP du LoadBalancer
nginx-ingress controller → reçoit le trafic 80/443, route vers les services
cert-manager             → automatise le cycle de vie des certificats TLS
Let's Encrypt (ACME)     → CA gratuite, émet des certificats valides 90 jours
```

**Rôles des composants** :

| Composant | Rôle |
|-----------|------|
| `ClusterIssuer` | Configure cert-manager pour utiliser Let's Encrypt (compte ACME, méthode challenge) |
| `Certificate` | Créé automatiquement par cert-manager depuis l'annotation Ingress |
| `ACME HTTP01` | Let's Encrypt fait un GET sur `/.well-known/acme-challenge/...` pour prouver que tu possèdes le domaine |
| `Secret kube-train-tls` | Stocke le certificat TLS émis, monté par nginx-ingress |

**Flow d'émission d'un certificat** :
```
kubectl apply ingress (annotation cert-manager.io/cluster-issuer)
  → cert-manager crée un Certificate + une Challenge
  → cert-manager déploie un pod temporaire qui répond à /.well-known/acme-challenge/...
  → nginx-ingress route la requête de Let's Encrypt vers ce pod
  → Let's Encrypt valide → émet le certificat
  → cert-manager stocke dans Secret kube-train-tls
  → nginx-ingress sert le certificat sur port 443
```

**GKE Autopilot — piège connu** :
- Installation raw YAML : cainjector essaie de créer des `leases` dans `kube-system` → **bloqué** par GKE Warden
- Solution : installer via **Helm** avec `--set global.leaderElection.namespace=cert-manager`
- Sans cainjector → CA bundle non injecté → webhook TLS cassé → `x509: certificate signed by unknown authority`

**Commandes de vérification** :
```bash
kubectl get certificate                          # READY=True = certificat émis
kubectl describe certificate kube-train-tls      # détails + dates expiration
kubectl get clusterissuer letsencrypt-prod        # READY=True = compte ACME enregistré
curl -v https://api.X.X.X.X.nip.io/ 2>&1 | grep issuer  # vérifier Let's Encrypt
```

**nip.io** : service DNS public gratuit. `api.1.2.3.4.nip.io` résout toujours vers `1.2.3.4`. Pas d'inscription, fonctionne immédiatement. Idéal pour les formations/démos sans domaine.

---

### Cloud SQL + Spring Data JPA + Auth Proxy

**Architecture de connexion sur GKE Autopilot** :
```
Pod
 ├─ api-container (Spring Boot)
 │    └─ JDBC → 127.0.0.1:5432
 └─ cloud-sql-proxy (sidecar)
      └─ tunnel chiffré → Cloud SQL instance (Cloud SQL Auth Proxy v2)
```
Le sidecar crée un tunnel IAM-authentifié → **pas d'IP publique exposée, pas de mot de passe réseau**.

**Workload Identity (obligatoire sur GKE Autopilot)** :
- Les pods GKE Autopilot n'héritent PAS du SA du nœud → le proxy obtient 403 sans Workload Identity
- Deux étapes requises :
```bash
# 1. Liaison IAM (niveau GCP)
gcloud iam service-accounts add-iam-policy-binding SA_COMPUTE \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:PROJECT.svc.id.goog[default/default]"

# 2. Annotation K8s (niveau cluster)
kubectl annotate serviceaccount default \
  iam.gke.io/gcp-service-account=SA_COMPUTE
```

**Profil Spring — stratégie dual-storage** :
```
Profil "default" (local, tests) : ReservationRepository == null → ConcurrentHashMap
Profil "postgres" (GKE)          : ReservationRepository injecté → Cloud SQL
```
```java
@Autowired(required = false)  // null si le bean n'existe pas (pas de DataSource)
private ReservationRepository reservationRepository;
```

**Spring Boot 4 — noms d'autoconfiguration (renommés depuis Boot 3)** :
```properties
# Dans spring.autoconfigure.exclude :
org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
```
⚠️ Les noms Spring Boot 3.x (`org.springframework.boot.autoconfigure.jdbc.*`) ne fonctionnent **pas** en Boot 4.

**HikariCP — timing avec le sidecar proxy** :
```properties
# Évite que HikariPool expire avant que le proxy soit prêt
spring.datasource.hikari.initialization-fail-timeout=-1
spring.flyway.connect-retries=10
spring.flyway.connect-retries-interval=5
```

**Piège mot de passe** : `gcloud sql users create` et `gcloud secrets create` sont deux opérations séparées.
Si faits à des moments différents avec des valeurs différentes → `FATAL: password authentication failed`.
Resync : `PASS=$(gcloud secrets versions access latest --secret=db-password); gcloud sql users set-password user --password="$PASS"`

**Proxy distroless** : l'image `gcr.io/cloud-sql-connectors/cloud-sql-proxy:2` n'a pas de shell.
`kubectl exec -c cloud-sql-proxy /bin/sh` → échec. Utiliser `-c api-container` ou Cloud Shell.

---

### Cloud Logging — Logs structurés JSON

**Activation sur GKE** (sans impact local) :
```yaml
# deployment-gke.yaml
- name: LOGGING_STRUCTURED_FORMAT_CONSOLE
  value: "ecs"
```
Format **ECS (Elastic Common Schema)** → Cloud Logging parse `log.level` comme severity.
Logs locaux restent en texte lisible (la variable n'est pas définie hors GKE).

**Structure d'un log ECS** :
```json
{
  "@timestamp": "2026-05-05T14:07:37.102Z",
  "log": { "level": "INFO", "logger": "com.kubetrain.api.controller.TrainController" },
  "service": { "name": "kube-train-api", "version": "0.0.1-SNAPSHOT" },
  "message": "POST /reservations — passager=Jean Dupont, train=TGV-7042"
}
```
→ Severity colorée dans Cloud Logging : 🟢 INFO, 🟡 WARN, 🔴 ERROR

**Requêtes LQL utiles** :
```
# Logs applicatifs seulement (filtre le bruit K8s/Hibernate)
resource.type="k8s_container"
resource.labels.container_name="api-container"
jsonPayload.log.logger=~"com.kubetrain"

# Erreur 500
resource.type="k8s_container"
resource.labels.container_name="api-container"
jsonPayload.message=~"Erreur interne"

# Tracer une réservation spécifique
resource.type="k8s_container"
jsonPayload.message=~"RES-B313EF9C"
```
→ En production : taper l'ID de transaction d'un client → reconstituer tout le flux en 2 secondes.

---

### Cloud Monitoring — Log-based Metrics & Alertes

**Log-based Metric** : transformer un pattern de log en métrique Cloud Monitoring.
```
Log filter: jsonPayload.message=~"persistée en Cloud SQL"
Metric type: Compteur → exposée comme logging/user/reservations_created (/s)
```
→ Toute métrique Log-based est utilisable dans les alertes, dashboards, SLOs.

**Unités** : Cloud Monitoring exprime les compteurs en **taux `/s`**.
Conversion : 10 réservations/min = 10 ÷ 60 = **0.167 /s**

**Types de condition d'alerte** :

| Type | Quand utiliser |
|------|---------------|
| **Threshold** | Déclenche si la valeur dépasse un seuil pendant N minutes |
| **Metric absence** | Déclenche si aucune donnée n'arrive pendant N minutes |
| **Forecast** | Déclenche si la tendance projette un dépassement futur |

**"Fenêtre du nouveau test"** : durée pendant laquelle le seuil doit être dépassé **en continu** avant de déclencher. Évite les faux positifs sur des pics courts. En prod : 5-15 min.

**Runbook dans la Documentation de l'alerte** : toujours documenter les étapes de diagnostic
en Markdown (liens kubectl, Cloud Logging, dashboards). L'équipe d'astreinte les voit directement dans l'email d'alerte.

---

### SLI / SLO / Error Budget

**Définitions** :
```
SLI (Indicator) = mesure brute
  → "95.3% des requêtes sont OK"

SLO (Objective) = cible interne de l'équipe engineering
  → "99.9% des requêtes OK sur 30 jours glissants"

SLA (Agreement) = contrat avec les clients
  → "On garantit 99.5%" (toujours < SLO pour garder une marge)
  → violation = pénalités financières

Error Budget = tolérance autorisée = 100% - SLO
  → SLO 99.9% sur 30 jours = 0.1% × 43 200 min = 43 min de downtime/mois
```

**"Three nines" courants** :

| SLO | Downtime/mois | Contexte typique |
|-----|--------------|-----------------|
| 99% | 7h 12min | Batch, outils internes |
| 99.9% | 43 min | API e-commerce |
| 99.95% | 21 min | Paiement, santé |
| 99.99% | 4 min | Très difficile avec des déploiements fréquents |

**Error Budget et freeze des déploiements** :
```
Budget consommé à 80% → alerte → ralentir les déploiements risqués
Budget épuisé (100%)  → freeze des features → focus fiabilité uniquement
Nouveau mois          → budget reset (si calendarPeriod) ou fenêtre glissante
```

**Question entretien type** :
> *"Vous avez une feature urgente mais votre error budget est à 20%. Que faites-vous ?"*
> → "On ne déploie pas. L'error budget est épuisé, on focalise sur la fiabilité. La feature attend."

**SLI à fenêtres (window-based)** :
Cloud Monitoring découpe la période en fenêtres de durée fixe (ex: 5 min).
Chaque fenêtre est évaluée "good" ou "bad" → `SLI = fenêtres good / fenêtres totales`.

**JSON d'un SLO Cloud Monitoring** :
```json
{
  "goal": 0.999,
  "calendarPeriod": "MONTH",
  "serviceLevelIndicator": {
    "windowsBased": {
      "windowPeriod": "300s",
      "metricMeanInRange": {
        "timeSeries": "metric.type=\"kubernetes.io/container/uptime\"",
        "range": { "min": 0, "max": 9007199254740991 }
      }
    }
  }
}
```
`calendarPeriod: MONTH` = reset le 1er du mois.
`rollingPeriod: "2592000s"` (30 jours) = fenêtre glissante, plus courant en prod.

---

## 📝 J4 Matin — Notes de révision : 12 Factors, Conteneurs immuables, GitOps

### Les 12 Factors appliqués à kube-train

Les 12 Factors (https://12factor.net) sont le **manifeste de l'app cloud-native**, écrit par les créateurs de Heroku en 2011. Connaître ces principes par cœur est un signal fort en entretien.

#### Factor 1 — Codebase ✅
> *"Un seul repo, plusieurs déploiements"*

Un seul repo Git (`samiyc/kube-train`), le même code tourne en local (Minikube) et sur GKE. Ce qui change entre les environnements, c'est la **config**, jamais le code.

#### Factor 2 — Dependencies ✅
> *"Déclarer et isoler explicitement les dépendances"*

`pom.xml` déclare tout (Spring, Kafka, Micrometer…). L'image Docker embarque exactement ce qui est déclaré, rien de plus. Jamais de `apt install` dans un Dockerfile.

#### Factor 3 — Config ✅
> *"Stocker la config dans l'environnement, jamais dans le code"*

Règle de test : **si le repo est rendu public, aucun secret ne doit fuiter**.

| Config | Mécanisme kube-train |
|--------|----------------------|
| API_KEY | GCP Secret Manager → K8s Secret → env var |
| DB_URL | ConfigMap → env var |
| Kafka URL | env var `KAFKA_BOOTSTRAP_SERVERS` |
| Message d'accueil | ConfigMap `TRAIN_MESSAGE` |

```java
@Value("${train.message:Bienvenue}") // valeur par défaut si absent
private String message;
```

#### Factor 4 — Backing Services ✅
> *"Traiter BDD, Kafka, cache comme des ressources attachées"*

Une **backing service** = tout ce que l'app consomme via le réseau. On doit pouvoir **swapper** la ressource sans changer le code, juste la config :

```yaml
# Changer de BDD = changer une env var, pas du code
env:
  - name: SPRING_DATASOURCE_URL
    value: "jdbc:postgresql://cloud-sql-proxy:5432/kube_train"
```

kube-train illustre ça parfaitement : même code, Cloud SQL en prod, H2 en test.

#### Factor 5 — Build / Release / Run ✅
> *"Séparer strictement les phases build, release, run"*

```
BUILD   : mvn package → kube-train-api.jar       (code compilé)
RELEASE : docker build → image:abc123f            (code + config figée)
RUN     : kubectl apply → Pod                     (release en exécution)
```

Le pipeline GitHub Actions implémente exactement ces trois phases. Point clé entretien : *"On ne patche jamais une image en prod. Un fix = nouvelle image via la pipeline."*

#### Factor 6 — Processes ⚠️ Dette technique identifiée
> *"Exécuter l'app comme des processus stateless"*

```java
// TrainService.java — profil "default" (local seulement)
private final Map<String, ReservationResponse> reservations = new ConcurrentHashMap<>();
// ⚠️ Si 2 pods tournent, chaque pod a sa propre Map → incohérence de données !
```

**En prod (GKE, profil `postgres`)** : ✅ stateless, tout est en Cloud SQL.  
**En local (profil `default`)** : ⚠️ stateful — acceptable pour le dev, interdit en prod.

> **Réponse entretien** : *"J'ai identifié cette dette. Elle est isolée au profil default. En prod on utilise Cloud SQL. La correction serait Redis ou une BDD partagée si on voulait du stateful en multi-pod."*

#### Factor 7 — Port Binding ✅
> *"L'app expose un service via un port, elle ne dépend pas d'un serveur externe"*

Spring Boot **embarque** Tomcat — il ne se déploie pas dans un Tomcat externe. L'app est autonome :
```
kube-train-api :8080 → Service K8s :80 → Ingress :443
```

#### Factor 8 — Concurrency ✅
> *"Scaler horizontalement, pas verticalement"*

```yaml
replicas: 2  # 2 instances identiques, K8s load-balance automatiquement
```
```bash
kubectl scale deployment kube-train-deployment --replicas=5  # scale instantané
```
C'est pourquoi le Factor 6 (stateless) est critique : sans stateless, le scaling horizontal crée des incohérences.

#### Factor 9 — Disposability ✅
> *"Démarrage rapide, arrêt propre"*

Les trois probes dans `deployment-gke.yaml` implémentent ce principe :
- `startupProbe` : attend que Spring ait démarré (~38s sur GKE Autopilot)
- `livenessProbe` : redémarre le pod si bloqué
- `readinessProbe` : retire du load-balancer si pas prêt à servir

Spring Boot gère le **graceful shutdown** automatiquement depuis Boot 2.3 : il finit les requêtes en cours avant de s'arrêter.

#### Factor 10 — Dev/Prod Parity ✅
> *"Garder dev, staging, prod aussi similaires que possible"*

| | Local | GKE |
|-|-------|-----|
| Image Docker | ✅ même Dockerfile | ✅ même Dockerfile |
| Config | profil `default` | profil `postgres` + env vars |
| Différence assumée | `imagePullPolicy: Never` | `imagePullPolicy: Always` |

Le seul vrai écart (BDD in-memory vs Cloud SQL) est documenté et limité au profil `default`.

#### Factor 11 — Logs ✅
> *"Traiter les logs comme des flux d'événements (stdout)"*

```java
log.info("Réservation {} persistée en Cloud SQL", reservation.getReservationId());
// → stdout → Fluent Bit (GKE) → Cloud Logging  (automatique)
```

L'app **ne sait pas** où vont ses logs. Elle écrit sur stdout, l'infrastructure collecte. Mis en place en J3 avec `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.

#### Factor 12 — Admin Processes
> *"Exécuter les tâches admin comme des one-off processes"*

```bash
# ❌ Anti-pattern : SSH dans le pod pour lancer un script
kubectl exec -it pod -- bash -c "psql ... < migration.sql"

# ✅ 12-Factor : un Job Kubernetes one-shot
kubectl run migration --image=kube-train-api:v5 --restart=Never -- java -jar app.jar --migrate
```

Flyway (migration J5) implémente ce principe : la migration s'exécute au démarrage, de façon traçable et reproductible.

---

### Conteneurs immuables

> **On ne modifie jamais un conteneur qui tourne. On crée une nouvelle image.**

```
❌ kubectl exec pod -- sed -i 's/old/new/' config.yaml  # interdit
✅ Modifier → commit → pipeline → nouvelle image → rolling update
```

Implications concrètes :
- Toute la config vient de l'**extérieur** (env vars, ConfigMap, Secret) — jamais baked dans l'image
- L'image est taguée par git SHA → auditabilité totale (qui a déployé quoi, quand)
- Rollback = revenir à l'image précédente (`kubectl rollout undo deployment/kube-train-deployment`)

---

### GitOps (théorie)

kube-train utilise du **CI/CD push-based** (GitHub Actions). GitOps est la prochaine évolution.

| | GitHub Actions (push-based) | GitOps / ArgoCD (pull-based) |
|-|----------------------------|------------------------------|
| Déclencheur | `push main` → pipeline active | Agent surveille le repo en continu |
| Qui applique | Pipeline `kubectl apply` depuis l'extérieur | Agent **dans** le cluster (pull) |
| Source de vérité | Code + pipeline YAML | Repo Git **uniquement** |
| Drift detection | ❌ Non (kubectl manual passe inaperçu) | ✅ Alerte si cluster ≠ Git |
| Rollback | Re-run pipeline sur ancien commit | `git revert` → sync automatique |

**ArgoCD** : un pod dans le cluster qui surveille le repo Git. Dès qu'un manifest change, il synchronise. Si quelqu'un fait un `kubectl apply` manuellement → ArgoCD détecte le drift et revient à l'état Git.

> **Phrase clé entretien** : *"Pour l'instant j'utilise GitHub Actions (push-based). La prochaine évolution serait ArgoCD pour du GitOps pull-based : drift detection automatique et rollback via simple `git revert`."*

---

## 📝 J4 Après-midi — Notes de révision : Les 3 piliers de l'observabilité & Datadog

### Les 3 piliers de l'observabilité

> *"Sans observabilité, un système en prod est une boîte noire."*

| Pilier | Question à laquelle il répond | Outil kube-train |
|--------|-------------------------------|------------------|
| **Logs** | Qu'est-ce qui s'est passé ? | Cloud Logging / Fluent Bit |
| **Métriques** | Combien ? Quelle tendance ? | Micrometer + Prometheus |
| **Traces** | Pourquoi c'est lent / cassé ? | OpenTelemetry (à implémenter) |

---

### Pilier 1 — Logs ✅ (implémenté en J3)

```
kube-train-api  →  stdout ECS JSON  →  Fluent Bit  →  Cloud Logging
```

LQL utile : `jsonPayload.message=~"RES-89F25868"` → tout le cycle de vie d'une réservation en 3 lignes.

---

### Pilier 2 — Métriques ✅ (implémenté en J4)

#### Micrometer = SLF4J des métriques

```
SLF4J      → abstraction → Logback / Log4j
Micrometer → abstraction → Prometheus / Datadog / CloudWatch / ...
```

Changer de backend = changer le registry dans `pom.xml`. Le code applicatif ne change pas.

#### Métriques implémentées dans kube-train

```java
// TrainService.java
meterRegistry.counter("reservations.created", "train_id", train.id()).increment();

// ReservationEventConsumer.java
meterRegistry.counter("notifications.processed", "train_id", event.trainId()).increment();
```

#### Contenu de `/actuator/prometheus`

```
# TYPE reservations_created_total counter
reservations_created_total{train_id="TGV-7042"} 14.0
reservations_created_total{train_id="TER-2814"} 3.0

# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap"} 1.23456789E8

# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{method="GET",status="200",uri="/trains"} 47.0
```

Spring Boot ajoute automatiquement des métriques JVM, HTTP, BDD et Kafka — des dizaines de métriques sans écrire une ligne de code.

#### Tags = dimensions d'analyse

```java
// ❌ Sans tag — total uniquement, pas filtrable
meterRegistry.counter("reservations.created").increment();

// ✅ Avec tag — filtrable par train dans Grafana/Prometheus
meterRegistry.counter("reservations.created", "train_id", train.id()).increment();
// → reservations_created_total{train_id="TGV-7042"} 14.0
// → reservations_created_total{train_id="TER-2814"} 3.0
```

#### Prometheus + Grafana sur Minikube

Prometheus doit être configuré pour **scraper** l'app via un `ServiceMonitor` (CRD fourni par le chart `kube-prometheus-stack`) :

```yaml
# k8s/observability/servicemonitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: kube-train-monitor
  labels:
    release: monitoring   # doit matcher le label du chart Prometheus
spec:
  selector:
    matchLabels:
      app: kube-train      # sélectionne le Service de l'app
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

Sans `ServiceMonitor`, Grafana Explore liste les métriques système K8s mais pas les métriques de l'app. Le `ServiceMonitor` est le "câble" qui branche Prometheus sur `/actuator/prometheus`.

---

### Pilier 3 — Traces distribuées (théorie)

Les logs disent **quoi**, les métriques disent **combien**, les traces disent **pourquoi c'est lent**.

#### Le problème sans tracing

```
Client → kube-train-api (50ms) → Kafka → notification-service (200ms)
                                                    ↑
                               Pourquoi 200ms ici ? Impossible à savoir sans trace
```

#### OpenTelemetry (OTel)

Standard CNCF unifiant traces, métriques et logs. Une **trace** = l'arbre complet d'une requête à travers tous les services :

```
Trace: POST /reservations  [total: 47ms]
  ├─ Span: TrainController.createReservation     [2ms]
  ├─ Span: TrainService.save                     [12ms]
  │    └─ Span: SQL INSERT reservations          [11ms]  ← goulot d'étranglement
  └─ Span: KafkaProducer.send                    [31ms]  ← latence Kafka
```

Chaque requête reçoit un **Trace ID** propagé dans les headers HTTP et messages Kafka :
```
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
```

#### Pour kube-train (si on l'ajoutait)

```xml
<!-- pom.xml — Spring Boot auto-configure le tracing -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

Les Trace IDs apparaissent automatiquement dans les logs JSON, permettant de corréler un log avec sa trace complète.

---

### Datadog — Vue d'ensemble (sans install, payant)

Datadog est la plateforme d'observabilité la plus répandue en entreprise. Ses 6 modules :

| Module | Rôle | Analogie kube-train |
|--------|------|---------------------|
| **APM** | Traces distribuées + profiling code | Voir que le `SQL INSERT` prend 11ms |
| **Infrastructure** | CPU, RAM, réseau des pods/nodes | Voir que GKE Autopilot scale à 3 nodes |
| **Log Management** | Agrégation + parsing + alertes | Remplace Cloud Logging |
| **Synthetics** | Tests de disponibilité planifiés | Appel `GET /` toutes les 5 min depuis Paris |
| **RUM** | Real User Monitoring (frontend) | Temps de chargement du Swagger UI |
| **Dashboards & Monitors** | Visualisation + alertes | Remplace Cloud Monitoring |

#### APM — le plus demandé en entretien

```
Sans APM : "L'API est lente" → on ne sait pas pourquoi
Avec APM : "Le SQL SELECT trains prend 340ms à cause d'un index manquant sur train_id"
```

L'agent Datadog s'installe comme **sidecar** dans le pod (même pattern que le Cloud SQL Auth Proxy) :

```yaml
containers:
  - name: api-container
    env:
      - name: DD_AGENT_HOST
        valueFrom:
          fieldRef:
            fieldPath: status.hostIP
```

#### Synthetics — monitoring externe

Datadog envoie des requêtes réelles depuis ses data centers dans le monde. Si `/actuator/health` répond > 2s depuis Tokyo → alerte. C'est un monitoring **externe** (différent des probes K8s qui sont internes au cluster).

---

### Récap — Le discours observabilité en entretien

> *"Dans kube-train, j'ai mis en place les 3 piliers de l'observabilité :*
> - *Logs : stdout JSON format ECS, collectés par Fluent Bit vers Cloud Logging. LQL queries pour tracer une réservation de bout en bout par son ID.*
> - *Métriques : Micrometer avec registry Prometheus, counters custom `reservations.created` par train_id, endpoint `/actuator/prometheus` exposé. Spring Boot ajoute automatiquement JVM, HTTP et BDD metrics.*
> - *Traces : pas encore implémenté — l'étape naturelle serait `micrometer-tracing-bridge-otel` pour propager les Trace IDs entre l'API et le notification-service via Kafka."*

---

## 📝 J5 Matin — Notes de révision : Pub/Sub on GKE (remplacement de Kafka pour cloud-native)

### GCP Pub/Sub vs Kafka — Quand utiliser lequel

| Critère | GCP Pub/Sub | Apache Kafka |
|---------|-------------|--------------|
| Infra | Serverless, rien à gérer | Self-managed (ou Confluent Cloud) |
| Scaling | Auto-scale transparent | Partitions manuelles |
| Coût | Pay-per-message | Cluster toujours allumé |
| Replay | Rétention 7j (max 31j) | Rétention illimitée, offset replay |
| Cas idéal | Event-driven cloud-native, microservices GCP | Event sourcing, streaming haute volumétrie |
| IAM | Natif GCP (Workload Identity) | SASL/SCRAM ou mTLS |
| DLQ | Intégrée (max delivery attempts) | Manuelle (topic DLT + consumer dédié) |

**Choix Pub/Sub pour kube-train sur GKE** :
- Moins de quota consommée (pas de cluster Kafka 3 brokers)
- Pas de cluster Zookeeper/KRaft à gérer en prod
- IAM natif via Workload Identity (déjà configuré pour Cloud SQL)
- DLQ intégrée sans code supplémentaire

> **⚠️ Piège entretien** : *"Kafka est toujours mieux que Pub/Sub"* → FAUX. Pub/Sub est supérieur pour du cloud-native GCP pur. Kafka gagne quand on a besoin de replay infini, event sourcing, ou multi-cloud.

---

### Architecture Pub/Sub dans kube-train

```
┌──────────────────┐      Pub/Sub topic             ┌──────────────────────────┐
│  kube-train-api  │───── "train-reservations" ────>│  notification-service    │
│  (publisher)     │                                │  (subscriber)            │
│  @Profile("gcp") │                                │  @Profile("gcp")         │
└──────────────────┘                                └──────────────────────────┘
                                                               │
                                                     max 5 delivery attempts
                                                               │    (nack)
                                                               ▼
                                                    ┌─────────────────────────┐
                                                    │ train-reservations-dlq  │
                                                    │ (Dead Letter)           │
                                                    └─────────────────────────┘
```

**Composants Pub/Sub** :
- **Topic** `train-reservations` : canal de messages (équivalent topic Kafka)
- **Subscription** `notification-subscription` : attachée au topic, pull-based par le consumer
- **DLQ topic** `train-reservations-dlq` : reçoit les messages après 5 échecs de livraison
- **ack-deadline** : 60s pour traiter un message avant qu'il soit re-livré

---

### Spring Profiles — Stratégie Dual Messaging

L'objectif : **même code, deux implémentations de messaging** selon l'environnement.

```
┌──────────────────────────────────────────────────────┐
│ Interface: ReservationEventPublisher                 │
│   void publish(ReservationEvent event)               │
├──────────────────────────────────────────────────────┤
│ @Profile("gcp")  → PubSubReservationEventPublisher   │ GKE
│ @Profile("!gcp") → KafkaReservationEventPublisher    │ Local Docker
│ (si kafka disabled) → NoOpReservationEventPublisher  │ Sans Kafka
└──────────────────────────────────────────────────────┘
```

**Configuration par profil** :

| Fichier | Contenu clé |
|---------|-------------|
| `application.properties` | Config par défaut (Kafka local, in-memory) |
| `application-postgres.properties` | Datasource Cloud SQL |
| `application-gcp.properties` | `spring.autoconfigure.exclude=...KafkaAutoConfiguration` |

```properties
# application-gcp.properties
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
```

**deployment-gke.yaml** :
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "postgres,gcp"
```

> **💡 Distinction importante** :
> - `@Profile("gcp")` → gate un **bean entier** (ou une classe `@Configuration`)
> - `@ConditionalOnProperty` → gate un **bean individuel** selon une property
> - Les profils Spring gèrent des **arbres de beans**, les conditionals gèrent des **feuilles**

---

### PubSubReservationEventPublisher — Implémentation

```java
@Service
@Profile("gcp")
public class PubSubReservationEventPublisher implements ReservationEventPublisher {

    private Publisher publisher;
    private final String topicName = "train-reservations";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws IOException {
        TopicName topic = TopicName.of("kube-train-project", topicName);
        publisher = Publisher.newBuilder(topic).build();
        // ADC (Application Default Credentials) — automatique sur GKE via metadata server
    }

    @Override
    public void publish(ReservationEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(json))
                .putAttributes("reservationId", event.reservationId())
                .build();

            // Blocking get() — même pattern que Kafka sync
            String messageId = publisher.publish(message).get(10, TimeUnit.SECONDS);
            log.info("Published to Pub/Sub, messageId={}", messageId);
        } catch (Exception e) {
            throw new RuntimeException("Pub/Sub publish failed", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (publisher != null) {
            publisher.shutdown();
        }
    }
}
```

**Points clés** :
- `@PostConstruct` / `@PreDestroy` pour le lifecycle du `Publisher` (connexion gRPC)
- **ADC** (Application Default Credentials) : sur GKE, le metadata server fournit les credentials automatiquement via Workload Identity. Pas de JSON key file.
- `reservationId` en **attribut** du message (metadata) → filtrable côté subscription
- `get(10, SECONDS)` bloquant pour garantir la livraison (comme `kafkaTemplate.send().get()`)

---

### PubSubReservationEventConsumer — Implémentation

```java
@Service
@Profile("gcp")
public class PubSubReservationEventConsumer {

    private Subscriber subscriber;
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void start() {
        ProjectSubscriptionName subscription = ProjectSubscriptionName.of(
            "kube-train-project", "notification-subscription");

        MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
            String messageId = message.getMessageId();

            // Idempotence — même pattern que Kafka
            if (!processedMessages.add(messageId)) {
                log.warn("Message déjà traité: {}", messageId);
                consumer.ack();
                return;
            }

            try {
                String json = message.getData().toStringUtf8();
                ReservationEvent event = objectMapper.readValue(json, ReservationEvent.class);
                log.info("📧 Email envoyé (simulé) pour réservation {}", event.reservationId());
                consumer.ack();   // ✅ succès → acknowledge
            } catch (Exception e) {
                log.error("Erreur traitement message: {}", e.getMessage());
                consumer.nack();  // ❌ échec → retry → DLQ après 5 tentatives
            }
        };

        subscriber = Subscriber.newBuilder(subscription, receiver).build();
        subscriber.startAsync().awaitRunning();
        log.info("Pub/Sub subscriber démarré sur {}", subscription);
    }

    @PreDestroy
    public void stop() {
        if (subscriber != null) {
            subscriber.stopAsync();
        }
    }
}
```

**Points clés** :
- `MessageReceiver` : interface fonctionnelle `(PubsubMessage, AckReplyConsumer) → void`
- `consumer.ack()` : message traité avec succès, ne sera plus re-livré
- `consumer.nack()` : message échoué, sera re-livré après le backoff → DLQ après 5 tentatives
- **Idempotence** : `ConcurrentHashSet` sur `messageId` (fourni par Pub/Sub, unique par message)
- Le subscriber tourne en arrière-plan (`startAsync`) — contrairement à Kafka `@KafkaListener` qui est géré par Spring

> **⚠️ Piège entretien** : *"Comment garantir l'idempotence avec Pub/Sub ?"*
> → Pub/Sub garantit **at-least-once** delivery. Le consumer DOIT dédupliquer. On utilise le `messageId` natif (pas besoin d'un header custom comme en Kafka).

---

### Setup Pub/Sub — Commandes gcloud

```bash
# Créer les topics
gcloud pubsub topics create train-reservations
gcloud pubsub topics create train-reservations-dlq

# Créer la subscription avec DLQ intégrée
gcloud pubsub subscriptions create notification-subscription \
  --topic=train-reservations \
  --ack-deadline=60 \
  --max-delivery-attempts=5 \
  --dead-letter-topic=train-reservations-dlq

# IAM : autoriser Pub/Sub à écrire dans le DLQ topic
# (le service agent Pub/Sub a besoin du rôle publisher sur le DLQ)
gcloud pubsub topics add-iam-policy-binding train-reservations-dlq \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"

# Vérifier la config
gcloud pubsub subscriptions describe notification-subscription
```

**Paramètres importants** :
- `--ack-deadline=60` : le consumer a 60s pour `ack()` avant re-livraison
- `--max-delivery-attempts=5` : après 5 échecs → message envoyé dans le DLQ
- Le **service agent** Pub/Sub (`service-{PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com`) doit avoir `roles/pubsub.publisher` sur le DLQ topic sinon les messages échoués sont perdus silencieusement

---

### Piège Alpine + gRPC natif (bug résolu)

> **⚠️ Bug critique** : crash silencieux au démarrage sur GKE (SIGSEGV, pas d'exception Java)

**Symptôme** : le pod crash-loop sans aucun log Java. `kubectl logs` montre juste un signal kill.

**Cause** :
```
google-cloud-pubsub  →  dépend de gRPC  →  dépend de netty-tcnative
                                                    ↓
                                          Lib native SSL compilée pour glibc
                                                    ↓
                                          Alpine utilise musl libc
                                                    ↓
                                          SIGSEGV (segfault au chargement de la lib native)
```

**Fix** : changer l'image de base dans le `Dockerfile` :

```dockerfile
# ❌ AVANT — crash silencieux avec Pub/Sub SDK
FROM eclipse-temurin:21-jre-alpine

# ✅ APRÈS — fonctionne avec gRPC/netty-tcnative
FROM eclipse-temurin:21-jre-jammy
```

**Conséquence** : image plus grosse (~80MB → ~200MB), mais stable.

> **💡 Leçon clé** : avant d'utiliser Alpine, vérifier si vos dépendances incluent des **libs natives** (JNI, netty-tcnative, RocksDB, etc.). Si oui → utiliser une image glibc (Debian/Ubuntu).

| Image base | libc | Taille | Compatible gRPC natif |
|-----------|------|--------|----------------------|
| `21-jre-alpine` | musl | ~80MB | ❌ SIGSEGV |
| `21-jre-jammy` | glibc (Ubuntu 22.04) | ~200MB | ✅ |
| `21-jre-bookworm` | glibc (Debian 12) | ~210MB | ✅ |

---

## 📝 J5 Après-midi — Notes de révision : CI/CD Multi-service & Consolidation

### Pipeline CI/CD restructurée — 3 jobs

```
┌─────────┐     ┌──────────┐     ┌───────────┐
│  test   │────>│  build   │────>│  deploy   │
│ (Maven) │     │ (Docker) │     │ (kubectl) │
└─────────┘     └──────────┘     └───────────┘
     │               │               │
     ├─ test API     ├─ build API    ├─ Check cluster exists
     ├─ test notif   ├─ build notif  ├─ Annotate SA (Workload Identity)
     └─ fail fast    └─ push both    ├─ Apply manifests (sed image tags)
                                     └─ Apply Ingress HTTPS
```

**Avantages de la séparation en 3 jobs** :
1. **Feedback rapide** : si les tests échouent, on ne build/push pas → économie de temps et quotas
2. **Builds parallèles** : API et notification-service buildés en parallèle dans le job `build`
3. **Isolation des failures** : on sait immédiatement SI c'est un problème de tests, de build Docker, ou de deploy K8s
4. **Graceful skip** : le job `deploy` vérifie si le cluster GKE existe avant de déployer (évite erreur si cluster supprimé pour économiser les crédits)

```yaml
# Vérification cluster existence (dans le job deploy)
- name: Check if GKE cluster exists
  id: check-cluster
  run: |
    if gcloud container clusters describe kube-train-cluster \
       --region=europe-west1 --format="value(name)" 2>/dev/null; then
      echo "cluster_exists=true" >> $GITHUB_OUTPUT
    else
      echo "cluster_exists=true" >> $GITHUB_OUTPUT
      echo "⚠️ Cluster not found — skipping deploy"
    fi
```

---

### Workload Identity — Ré-annotation après recréation de cluster

**Problème** : après avoir supprimé et recréé le cluster GKE (pour économiser les crédits), le Cloud SQL Auth Proxy obtient une erreur `403 Permission Denied`.

**Explication** :

| Composant | Survit à la suppression du cluster ? |
|-----------|--------------------------------------|
| IAM binding (côté GCP) | ✅ Oui — c'est une policy GCP |
| `kubectl annotate` (côté K8s) | ❌ Non — l'annotation est dans etcd du cluster |

```bash
# L'annotation qui lie le ServiceAccount K8s au ServiceAccount GCP
kubectl annotate serviceaccount default \
  --namespace default \
  iam.gke.io/gcp-service-account=kube-train-sa@kube-train-project.iam.gserviceaccount.com \
  --overwrite
```

**Sans cette annotation** :
- Le pod démarre ✅
- L'API répond sur `/` ✅
- Cloud SQL Auth Proxy → `403 Permission Denied` ❌ (ne peut pas s'authentifier auprès de Cloud SQL)

**Solution CI/CD** : le pipeline inclut maintenant l'annotation systématiquement (idempotent grâce à `--overwrite`) :

```yaml
- name: Annotate SA for Workload Identity
  run: |
    kubectl annotate serviceaccount default \
      --namespace default \
      iam.gke.io/gcp-service-account=kube-train-sa@kube-train-project.iam.gserviceaccount.com \
      --overwrite
```

> **⚠️ Piège entretien** : *"Workload Identity ne marche plus après avoir recréé le cluster"*
> → L'IAM binding survit (côté GCP), mais l'**annotation K8s est perdue** (côté cluster). Il faut la ré-appliquer.

---

### Notification-service — Premier déploiement GKE

**Différences avec l'API** :

| | kube-train-api | notification-service |
|-|----------------|---------------------|
| Profils | `postgres,gcp` | `gcp` (pas de BDD) |
| Cloud SQL Proxy | ✅ sidecar (2/2 containers) | ❌ pas besoin (1/1) |
| Image placeholder | `IMAGE_TAG_PLACEHOLDER` | `NOTIFICATION_IMAGE_PLACEHOLDER` |
| Port exposé | 8080 (HTTP API) | Aucun (consumer only) |
| Probes | `/actuator/health` | `/actuator/health` |

```yaml
# notification-deployment-gke.yaml (extrait)
spec:
  containers:
    - name: notification-container
      image: NOTIFICATION_IMAGE_PLACEHOLDER
      env:
        - name: SPRING_PROFILES_ACTIVE
          value: "gcp"
```

**Pas de Service/Ingress** pour le notification-service : c'est un consumer pur (pas d'endpoint HTTP public). Il reçoit les messages via la subscription Pub/Sub.

---

### Tests — 27 tests au total

#### TrainServiceTest — 6 tests

```java
// Tests avec profil postgres (TrainServicePostgres)
@Test void shouldPersistReservation_postgres() { ... }
@Test void shouldGetReservation_postgres() { ... }
@Test void shouldThrow404_whenNotFound_postgres() { ... }

// Tests avec profil default (TrainServiceMemory)
@Test void shouldPersistReservation_memory() { ... }
@Test void shouldGetReservation_memory() { ... }
@Test void shouldThrow404_whenNotFound_memory() { ... }
```

#### KafkaReservationEventPublisherTest — 3 tests

```java
@Test void shouldPublishToCorrectTopic() { ... }
@Test void shouldNotPublishToWrongTopic() { ... }
@Test void shouldHandleKafkaDown() { ... }
```

#### PubSubReservationEventPublisherTest — 3 tests

```java
@Test void shouldPublishToPubSub() { ... }
@Test void shouldPublishExactlyOnce() { ... }
@Test void shouldHandlePubSubDown() { ... }
```

**Pattern de test** : `ReflectionTestUtils.setField()` pour injecter des mocks sans démarrer le contexte Spring :

```java
// Pas de @SpringBootTest → test unitaire rapide (< 100ms)
PubSubReservationEventPublisher publisher = new PubSubReservationEventPublisher();
Publisher mockPublisher = mock(Publisher.class);
ReflectionTestUtils.setField(publisher, "publisher", mockPublisher);

// Test
publisher.publish(new ReservationEvent("RES-123", "TGV-001", 2));
verify(mockPublisher).publish(any(PubsubMessage.class));
```

> **💡 Avantage** : pas de contexte Spring = tests en < 100ms. Parfait pour TDD et CI rapide.

---

### Phrase clé entretien — Pub/Sub

> *"En local on utilise Kafka via Docker Compose pour le dev rapide. En production sur GKE, on utilise Pub/Sub — serverless, pas de cluster Kafka à gérer, IAM natif, DLQ intégrée. Le switch est transparent grâce aux profils Spring : `@Profile('gcp')` active Pub/Sub, sans `gcp` c'est Kafka."*

---

### Validation end-to-end — Flow testé en prod

```
POST /reservations (Swagger UI)
    │
    ▼
PubSubReservationEventPublisher
    │
    ▼
Pub/Sub topic "train-reservations"
    │
    ▼
notification-subscription (pull)
    │
    ▼
PubSubReservationEventConsumer
    │
    ▼
log.info("📧 Email envoyé (simulé) pour réservation {}", event.reservationId())
```

**Vérification** :
```bash
# Logs de l'API (publisher)
kubectl logs -l app=kube-train-pod | grep "Published to Pub/Sub"

# Logs du notification-service (consumer)
kubectl logs -l app=notification-pod | grep "Email envoyé"
```

---

### Récap — Le discours Pub/Sub en entretien

> *"Dans kube-train, j'ai implémenté un système de messaging dual :*
> - *En local : Kafka via Docker Compose (docker-compose.yml avec KRaft, pas de Zookeeper). Consumer avec @KafkaListener, DLT manuelle via un topic dédié.*
> - *Sur GKE : Pub/Sub serverless. Publisher avec le SDK google-cloud-pubsub, subscriber avec MessageReceiver. DLQ native après 5 tentatives.*
> - *Le switch est invisible grâce aux profils Spring (@Profile('gcp') vs @Profile('!gcp')). Les deux implémentent la même interface ReservationEventPublisher.*
> - *Bug résolu : crash Alpine + gRPC natif (netty-tcnative/musl incompatible) → migration vers eclipse-temurin:21-jre-jammy.*
> - *27 tests unitaires dont 3 spécifiques Pub/Sub avec ReflectionTestUtils pour injecter des mocks sans contexte Spring."*

