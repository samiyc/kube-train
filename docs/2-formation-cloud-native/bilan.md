# 📊 Bilan — Formation 2 : Cloud-Native (5 jours)

**Dates** : mai 2026  
**Score QCM** : 20/25 (passé le 18/05/2026)

---

## Ce qui a été accompli

### Livrables techniques

| Jour | Sujet | Livrable concret |
|------|-------|------------------|
| J1 | API-First, Swagger, Gestion d'erreurs | Swagger UI, ProblemDetail RFC 9457, records Java, @RestControllerAdvice |
| J2 | Event-Driven avec Kafka | Docker Compose KRaft, notification-service, idempotence, DLT, multi-module Maven |
| J3 | Services GCP avancés | Secret Manager → pipeline, cert-manager HTTPS, Cloud SQL + Auth Proxy sidecar, Cloud Logging, SLI/SLO |
| J4 | 12 Factors, GitOps, Observabilité | Audit 12 factors, Micrometer + /actuator/prometheus, logging JSON ECS, concepts Datadog |
| J5 | Pub/Sub + CI/CD multi-service | Pub/Sub dual-messaging (@Profile), pipeline 3 jobs, notification-service sur GKE, 27 tests, fix Alpine/gRPC |

### Infra déployée sur GKE
- 2 pods : kube-train-api (2/2 containers avec Cloud SQL Proxy sidecar) + notification-service (1/1)
- Pipeline CI/CD complète : test → build → deploy (résiliente si cluster absent)
- HTTPS Let's Encrypt via cert-manager + nginx-ingress
- Cloud SQL PostgreSQL managé
- Pub/Sub avec DLQ native
- Workload Identity (pas de clé JSON)

### Compétences acquises (discours entretien)
- Architecture event-driven cloud-native de bout en bout
- Pipeline CI/CD push-based GitHub Actions complète
- Profils Spring pour dual-messaging (Kafka local / Pub/Sub prod)
- Kubernetes avancé : probes, sidecar, Workload Identity, Ingress HTTPS
- Observabilité : logs structurés, métriques custom, concepts SLI/SLO

---

## Points forts de la formation

1. **Approche "learn by doing"** — chaque concept a été implémenté sur kube-train, pas juste lu en théorie
2. **Stack réaliste** — GKE Autopilot, Cloud SQL, Pub/Sub, Secret Manager → c'est ce qu'on utilise en mission
3. **QCM type entretien** — les questions sont au niveau de ce que demandent les clients (Décathlon, etc.)
4. **Pipeline CI/CD complète** — du commit au pod en production, résiliente aux pannes infra
5. **Dual-messaging** — savoir jongler local/prod avec le même code grâce aux profils Spring

---

## Points d'amélioration / retours

### 🔴 HTTPS / cert-manager — trop verbeux, peu visuel
Les termes (ClusterIssuer, Certificate, CertificateRequest, Order, Challenge, ACME) sont abstraits. En pratique on le configure une fois et on n'y touche plus. Retenir le flux simplifié :
```
Ingress annotation → cert-manager → challenge HTTP-01 → Secret TLS → nginx
```
→ Un schéma serait plus utile que 5 paragraphes de texte.

### 🟡 Spring Boot 4 — instabilité
Plusieurs problèmes liés à Spring Boot 4 (préversion / breaking changes) :
- Packages `jakarta.*` au lieu de `javax.*`
- Changements de noms dans Kafka/PubSub starters
- Certaines configs deprecated sans documentation claire

→ En formation 3, épingler les versions et documenter les workarounds dès le jour 1.

### 🟡 QCM en fin de formation ≠ optimal
Relire 5 jours de notes d'un coup est lourd. 
→ **Formation 3 : QCM de 5 questions à la fin de chaque journée** pour ancrer les acquis progressivement.

### 🟢 Ce qui a bien marché
- Les "phrases clé entretien" dans les notes → prêtes à ressortir
- Les tables de comparaison (Kafka vs Pub/Sub, Alpine vs Jammy, etc.)
- Le fix Alpine/gRPC : bug réel, analyse réelle, solution documentée → anecdote entretien parfaite

---

## Sujets reportés en Formation 3

| Sujet | Raison du report | Priorité F3 |
|-------|------------------|-------------|
| Spring Cloud Contract | Trop lourd à implémenter en 1 demi-journée, théorie vue | J1 |
| Outbox Pattern | Nécessite Flyway + table outbox → dépendance | J1 |
| OpenTelemetry / Distributed Tracing | Besoin d'un collector (Tempo/Jaeger) → infra lourde | J2 |
| Flyway DB migrations | Prérequis pour Outbox, pas bloquant seul | J1 |

---

## Prochaines étapes

1. **Repasser le QCM** dans 2-3 semaines (après la formation 3) → mesurer la rétention
2. **Formation 3** : DevOps, Sécurité & Qualité (ArgoCD, OAuth2, OTel, Flyway, Cucumber, SonarCloud)
3. **Supprimer le cluster GKE** pour économiser les crédits (le pipeline gère la recréation)
4. Préparer le discours entretien à partir des notes J1-J5

---

## Architecture finale formation 2

```
┌─ GitHub ─────────────────────────────────────────────────────────────────┐
│  push main → GitHub Actions (3 jobs)                                     │
│  test (Maven × 2) → build (Docker × 2 → Artifact Registry) → deploy      │
└──────────────────────────────────────────────────────────────────────────┘
                                    │ 
                                    ▼ 
┌─ GKE Autopilot (europe-west1) ───────────────────────────────────────────┐
│                                                                          │
│  ┌─ kube-train-api (2/2) ─────────┐     ┌─ Pub/Sub ──────────────────┐   │
│  │  Spring Boot 4 (postgres, gcp) │     │  topic: train-reservations │   │
│  │  + Cloud SQL Auth Proxy sidecar│────>│  DLQ: train-reservations-  │   │
│  │  Swagger UI, /actuator/health  │     │       dlq (5 retries)      │   │
│  └────────────────────────────────┘     └─────────────┬──────────────┘   │
│           │                                           │                  │
│           ▼                                           ▼                  │
│  ┌─ Cloud SQL ─────────┐            ┌─ notification-service (1/1) ────┐  │
│  │  PostgreSQL 15      │            │  Spring Boot 4 (gcp)            │  │
│  │  db: kube_train     │            │  PubSubConsumer → log email     │  │
│  └─────────────────────┘            │  Idempotence: ConcurrentHashSet │  │
│                                     └─────────────────────────────────┘  │
│  nginx-ingress → HTTPS (cert-manager, Let's Encrypt)                     │
│  Secret Manager → API_KEY → K8s Secret (pipeline upsert)                 │
│  Workload Identity → IAM sans clé JSON                                   │
└──────────────────────────────────────────────────────────────────────────┘
```
