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
