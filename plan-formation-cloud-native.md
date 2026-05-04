# 🎯 Plan de formation — 5 jours (Cloud-Native, Event-Driven, DevOps)

> Objectif : Transformer kube-train en projet de référence cloud-native complet,
> couvrant les sujets à fort ROI pour les missions futures (Décathlon et au-delà).
> Chaque journée produit un livrable concret sur le projet.

---

## État des lieux — Acquis sur kube-train

| Sujet | Statut |
|---|---|
| Java 21 / Spring Boot / Maven | ✅ Maîtrisé |
| Docker / Dockerfile multi-stage | ✅ Fait |
| K8s : Pods, Deployments, Services, Ingress, ConfigMap, Secret, PVC, HPA, Probes | ✅ Fait |
| Minikube local | ✅ Fait |
| Load testing (Locust) | ✅ Fait |
| GKE Autopilot | ✅ Fait |
| Artifact Registry | ✅ Fait |
| GitHub Actions CI/CD | ✅ Fait |
| IAM / Service Accounts / Workload Identity Federation | ✅ Fait |
| HTTPS / Certificats | ❌ À faire |
| GCP Secret Manager | ❌ À faire |
| Cloud SQL (Postgres managé) | ❌ À faire |
| Pub/Sub / Kafka | ❌ À faire |
| Cloud Logging / Monitoring | ❌ À faire |
| API-First (OpenAPI contract-first) | ❌ À faire |
| Contract Testing (Spring Cloud Contract / Pact) | ❌ À faire |
| Event-Driven Architecture (patterns) | ❌ À faire |
| Observabilité (Datadog concepts) | ❌ À faire |

---

## Jour 1 — API-First & Contract Testing

**Objectif** : Passer de "code-first" à "contract-first". Savoir expliquer la différence en entretien et l'avoir pratiqué.

### Matin — API-First avec OpenAPI Generator

1. **Écrire le contrat OpenAPI** (YAML) pour kube-train-api :
   - `GET /` → message d'accueil
   - `GET /reserver` → réservation de billet (réponse JSON avec statut, wagon, horaire)
   - `GET /secure` → endpoint sécurisé (header X-API-KEY)
   - Définir les schémas de réponse, les codes d'erreur, les headers

2. **Générer le code serveur** avec `openapi-generator-maven-plugin` :
   - Ajouter le plugin dans `pom.xml`
   - Générer les interfaces Java depuis le YAML
   - Implémenter les interfaces dans `TrainController`
   - Le contrat YAML devient la source de vérité (pas le code Java)

3. **Exposer Swagger UI** :
   - `springdoc-openapi-starter-webmvc-ui` dans les dépendances
   - Accéder à `/swagger-ui.html` pour naviguer l'API

### Après-midi — Contract Testing

4. **Spring Cloud Contract** (consumer-driven contract testing) :
   - Ajouter un 2ème micro-service fictif (ex: `train-booking-service`) qui consomme l'API de `kube-train-api`
   - Écrire un contrat Groovy/YAML entre les deux services
   - Générer les stubs automatiquement pour le consumer
   - Le test vérifie que le producer respecte le contrat → si quelqu'un casse l'API, le test échoue

**Concepts à retenir pour l'entretien** :
- Contract-first vs Code-first
- Consumer-driven contracts
- Pourquoi c'est crucial en microservices (chaque équipe peut travailler indépendamment)
- Alternative : Pact (multi-langage) vs Spring Cloud Contract (écosystème Spring)

---

## Jour 2 — Event-Driven Architecture avec Kafka

**Objectif** : Ajouter Kafka à kube-train et implémenter les patterns clés demandés par Décathlon.

### Matin — Kafka local (Docker Compose)

1. **Comprendre les concepts fondamentaux** :
   - Topic / Partition / Consumer Group / Offset
   - Différence Kafka vs Pub/Sub vs RabbitMQ
   - Quand utiliser du messaging async vs REST sync

2. **Ajouter Kafka à kube-train** (Docker Compose pour dev local) :
   - Kafka + Zookeeper (ou KRaft mode sans Zookeeper)
   - Créer un topic `train-reservations`
   - `kube-train-api` publie un événement à chaque réservation (`/reserver`)
   - Créer un 2ème micro-service `train-notification-service` qui consomme les événements

3. **Spring Kafka** :
   - `@KafkaListener` pour le consumer
   - `KafkaTemplate` pour le producer
   - Sérialisation JSON avec `JsonSerializer` / `JsonDeserializer`

### Après-midi — Patterns Event-Driven avancés

4. **Idempotence** : s'assurer qu'un message reçu 2 fois ne crée pas 2 réservations
   - Pattern : stocker un `eventId` unique et vérifier avant de traiter

5. **Dead Letter Queue (DLT)** : quand un message est impossible à traiter
   - Configurer un topic `train-reservations-dlt` pour les messages en erreur

6. **Outbox Pattern** (théorie + schéma) :
   - Problème : écrire en BDD + publier un event Kafka = 2 opérations non-atomiques
   - Solution : écrire l'event dans une table `outbox` (même transaction), un poller le publie dans Kafka
   - Bonus : Debezium pour le CDC (Change Data Capture)

7. **Saga Pattern** (théorie) :
   - Transaction distribuée entre micro-services
   - Orchestration vs Chorégraphie
   - Compensation en cas d'échec

**Livrables** : kube-train avec Kafka fonctionnel en local, 2 micro-services qui communiquent en asynchrone.

---

## Jour 3 — GCP Services avancés

**Objectif** : Maîtriser les services GCP courants en entreprise, directement sur kube-train.

### Matin — GCP Secret Manager + HTTPS

1. **GCP Secret Manager** (remplacer les K8s secrets) :
   - Créer un secret dans GCP Secret Manager (`API_KEY`)
   - Utiliser `spring-cloud-gcp-starter-secretmanager` pour l'injecter directement dans Spring
   - Avantages : rotation automatique, audit trail, pas de secret dans les manifests K8s

2. **HTTPS avec un certificat géré par Google** :
   - Acheter/utiliser un domaine (ou gratuit via `nip.io` / `sslip.io` pour le TP)
   - Créer un `ManagedCertificate` GKE
   - Configurer un Ingress GKE avec le certificat
   - Alternative rapide : `cert-manager` + Let's Encrypt

### Après-midi — Cloud SQL & Cloud Logging

3. **Cloud SQL PostgreSQL** (remplacer le Postgres en pod) :
   - Créer une instance Cloud SQL (micro, pour les crédits)
   - Connecter kube-train via le Cloud SQL Auth Proxy (sidecar dans le pod)
   - Migrer la config de connexion depuis le `postgres-deployment.yaml` local

4. **Cloud Logging / Monitoring** :
   - Les logs de tes pods GKE sont déjà dans Cloud Logging (vérifier)
   - Créer un dashboard basique dans Cloud Monitoring
   - Configurer une alerte (ex: si le pod redémarre plus de 3 fois en 5 min)
   - Comprendre les concepts SLI / SLO / Error Budget (important pour Décathlon)

5. **Pub/Sub** (parallèle avec Kafka, 30 min théorie) :
   - Pub/Sub = le Kafka managé de Google (serverless, pas de cluster à gérer)
   - Différences clés : ordering, retention, exactly-once, replay
   - Quand utiliser l'un vs l'autre en entreprise

---

## Jour 4 — Cloud-Native Patterns & Observabilité

**Objectif** : Solidifier les concepts architecturaux pour le discours d'entretien.

### Matin — Les 12 Factors & Cloud-Native

1. **Les 12 Factors** (revue appliquée à kube-train) :
   - Codebase, Dependencies, Config, Backing Services, Build/Release/Run,
     Processes, Port Binding, Concurrency, Disposability, Dev/Prod Parity,
     Logs, Admin Processes
   - Pour chaque factor, montrer comment kube-train les respecte déjà
   - Identifier ce qui manque et corriger

2. **Conteneurs immuables** :
   - Pourquoi on ne modifie jamais un conteneur en cours d'exécution
   - Config externalisée (ConfigMap/Secret/SecretManager) ✅ déjà fait
   - Health checks ✅ déjà fait

3. **GitOps** (théorie) :
   - Le repo Git = la source de vérité du cluster
   - ArgoCD / Flux : synchro automatique Git → K8s
   - Différence avec le `kubectl apply` dans GitHub Actions (push-based vs pull-based)

### Après-midi — Observabilité (Datadog concepts)

4. **Les 3 piliers de l'observabilité** :
   - **Logs** : stdout structuré JSON (logback + logstash-encoder)
   - **Metrics** : Micrometer + Prometheus endpoint (`/actuator/prometheus`)
   - **Traces** : OpenTelemetry pour tracer une requête à travers les micro-services

5. **Datadog** (lecture doc, pas d'install — payant) :
   - APM (Application Performance Monitoring)
   - Infrastructure Monitoring (CPU, Memory, Pods)
   - Log Management
   - Synthetics (tests de disponibilité)
   - RUM (Real User Monitoring — frontend)
   - Dashboards & Monitors
   - SLO tracking

6. **Implémenter sur kube-train** :
   - Ajouter `micrometer-registry-prometheus` dans le `pom.xml`
   - Exposer `/actuator/prometheus`
   - Configurer le logging structuré JSON
   - Bonus : installer Prometheus + Grafana dans Minikube pour visualiser

---

## Jour 5 — Déployer le tout + Consolidation

**Objectif** : Intégrer les ajouts des jours 2-4 dans le pipeline GKE, et préparer le discours.

### Matin — Déploiement multi-services sur GKE

1. **Déployer Kafka sur GKE** (ou utiliser Pub/Sub comme alternative managée) :
   - Option A : Strimzi Kafka Operator sur GKE (plus réaliste mais consomme du quota)
   - Option B : Remplacer Kafka par Pub/Sub (plus cloud-native, moins de quota)
   - Déployer `train-notification-service` avec son propre Deployment/Service

2. **Mettre à jour le pipeline GitHub Actions** :
   - Build et push des 2 images (kube-train-api + notification-service)
   - Déployer les deux services

3. **Tester le flux end-to-end** :
   - `curl /reserver` → event Kafka/Pub/Sub → notification-service log l'événement
   - Vérifier dans Cloud Logging que les deux services logent correctement

### Après-midi — Consolidation & Discours

4. **Mettre à jour les docs** :
   - `deploy-kube-train-to-gcp.md` avec les nouvelles commandes
   - `readme.md` avec la nouvelle architecture (schéma)
   - Ajouter une Saison 6 dans la roadmap du README

5. **Préparer le discours d'entretien** :
   - Être capable de dessiner l'architecture complète de kube-train (2 services, Kafka, GKE, CI/CD, monitoring)
   - Préparer 1 slide mental par sujet :
     - API-First : "J'ai implémenté un contrat OpenAPI contract-first, avec génération de code serveur"
     - Event-Driven : "J'ai mis en place Kafka entre 2 micro-services avec idempotence et DLT"
     - Cloud-Native : "Déployé sur GKE Autopilot avec CI/CD GitHub Actions, Secret Manager, Cloud SQL"
     - Observabilité : "Logs structurés JSON, métriques Prometheus, alerting Cloud Monitoring"

---

## Architecture cible en fin de semaine

```
┌─ GitHub ───────────────────────────────────────────────────┐
│  push main → GitHub Actions                                │
│    1. mvn test                                             │
│    2. docker build × 2 (api + notification)                │
│    3. push → Artifact Registry                             │
│    4. deploy → GKE                                         │
└────────────────────────────────────────────────────────────┘

┌─ GKE Autopilot ───────────────────────────────────────────┐
│                                                            │
│  ┌─ kube-train-api ──┐    ┌─ Kafka / Pub/Sub ┐            │
│  │  GET /             │    │                   │            │
│  │  GET /reserver ────┼──>│  train-reservations│            │
│  │  GET /secure       │    │                   │            │
│  │  /actuator/health  │    └───────┬───────────┘            │
│  │  /actuator/prometheus│          │                        │
│  │  /swagger-ui.html  │           │                        │
│  └────────────────────┘           ▼                        │
│                          ┌─ notification-service ─┐        │
│                          │  Consomme les events    │        │
│                          │  Log / Envoie notif     │        │
│                          └─────────────────────────┘        │
│                                                            │
│  Cloud SQL Postgres ◄── kube-train-api                     │
│  Secret Manager ──► inject API_KEY                         │
│  Cloud Logging ◄── tous les pods                           │
│  Cloud Monitoring ◄── alertes                              │
└────────────────────────────────────────────────────────────┘
```

## Priorité si le temps manque

Si tu n'as que **3 jours** au lieu de 5, fais dans cet ordre :
1. **Jour 2 (Kafka/Event-Driven)** — le plus gros trou et le plus demandé
2. **Jour 3 (GCP Services)** — Secret Manager + Cloud SQL + Monitoring
3. **Jour 4 (Observabilité)** — les concepts Datadog + Prometheus

Les jours 1 (API-First) et 5 (intégration) sont importants mais moins urgents.

## Ressources utiles

- [12 Factor App](https://12factor.net/fr/)
- [Spring Cloud Contract docs](https://spring.io/projects/spring-cloud-contract)
- [Spring for Apache Kafka](https://docs.spring.io/spring-kafka/reference/)
- [GCP Secret Manager + Spring](https://cloud.google.com/secret-manager/docs/reference/libraries#client-libraries-install-java)
- [Datadog Learning Center](https://learn.datadoghq.com/)
- [OpenAPI Generator](https://openapi-generator.tech/)
- [Strimzi - Kafka on Kubernetes](https://strimzi.io/)


----


## 📋 Récap des APIs
```
┌─────────┬──────────────────────────┬──────────────────────────────────────────┬───────────────┐
│ Méthode │ URL                      │ Description                              │ Status codes  │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /                        │ Page d'accueil (message ConfigMap + pod) │ 200           │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /trains                  │ Liste tous les trains                    │ 200           │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /trains/{id}             │ Détail d'un train                        │ 200, 404      │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ POST    │ /reservations            │ Créer une réservation (JSON body)        │ 201, 400, 404 │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /reservations/{id}       │ Consulter une réservation                │ 200, 404      │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /secure                  │ Zone protégée (header X-API-KEY)         │ 200, 401      │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /actuator/health         │ Health check (K8s probes)                │ 200           │
├─────────┼──────────────────────────┼──────────────────────────────────────────┼───────────────┤
│ GET     │ /swagger-ui/index.html   │ Swagger UI interactif                    │ 200           │
└─────────┴──────────────────────────┴──────────────────────────────────────────┴───────────────┘
```
Test en local
```
# Lancer les deux application SpringBoot
IntelliJ : run kube-train-api  ← avec env-var KAFKA_ENABLED=true
IntelliJ : run notification

# Lancer le conteneur kafka dans le bash
# ou dans l'ide => Services
docker compose up -d

# Url du swagger pour tester les APIs
=> http://localhost:8080/swagger-ui/index.html
```
Check les logs Kafka (uniquement un conteneur pour le consumer et le producer)
```
docker exec -it kafka-kube-train /opt/kafka/bin/kafka-console-producer.sh \
   --bootstrap-server localhost:9092 --topic train-reservations
# Test DLT : {"invalid":"json pour tester le DLT"}

docker exec kafka-kube-train /opt/kafka/bin/kafka-console-consumer.sh \
   --bootstrap-server localhost:9092 --topic train-reservations --from-beginning
```
### 🏗️ Écarts vs production — audit rapide
```
┌───────────────────┬──────────────────────────┬───────────────────────────────────────┐
│ Aspect            │ Ce POC                   │ Production                            │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ BDD               │ In-memory (HashMap)      │ PostgreSQL/MySQL + JPA/Hibernate      │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Front             │ Swagger UI               │ React/Angular/Vue                     │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Auth              │ Header X-API-KEY basique │ OAuth2/OIDC (Keycloak, GCP IAP)       │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Observabilité     │ Logs console             │ Prometheus + Grafana + Cloud Logging  │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Config            │ application.properties   │ Spring Cloud Config / Secret Manager  │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Schema Kafka      │ JSON brut                │ Avro + Schema Registry                │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Tests             │ Unit + Contract          │ + Integration (Testcontainers) + E2E  │
├───────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ Résilience        │ Aucune                   │ Circuit Breaker (Resilience4j), Retry │
└───────────────────┴──────────────────────────┴───────────────────────────────────────┘
```

---

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
