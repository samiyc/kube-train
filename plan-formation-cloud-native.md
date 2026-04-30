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
   - Configurer un topic `train-reservations.DLT` pour les messages en erreur

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
mvnw spring-boot:run   # Ou start du springboot via l'ide
=> http://localhost:8080/swagger-ui/index.html
```

