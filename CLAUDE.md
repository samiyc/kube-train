# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Hands-on Kubernetes/cloud-native training project (5-day formation program, currently on **F4 — Platform Engineering**). A Spring Boot 4 API (`kube-train-api/`) and a notification micro-service (`train-notification-service/`) are deployed on GKE Autopilot via GitHub Actions CI/CD. Local development uses Minikube + Docker Compose.

Formation progression: F1 (Minikube) → F2 (Cloud Native GKE) → F3 (Beyond: OTel, ArgoCD, OAuth2) → **F4 (Platform Engineering: RBAC, Helm, Terraform, Istio, SRE)** — currently on F4-J4 (Istio).

## Environment

- **Windows** : Claude Code + IntelliJ s'exécutent sur Windows (PowerShell), projet dans `C:\DEVDIR\GITHUB\kube-train`
- **WSL** : toutes les commandes `minikube`, `docker`, `kubectl`, `gcloud`, **`helm`** se lancent depuis WSL (`wsl` ou terminal Ubuntu)
- Minikube utilise le driver Docker dans WSL
- Chemin WSL du projet : `/mnt/c/DEVDIR/GITHUB/kube-train`

## Build & test commands

```bash
# Local Maven (run from service subdirectory)
./mvnw spring-boot:run       # kube-train-api/ or train-notification-service/
./mvnw test                  # Full test suite (36 tests including 7 BDD Cucumber)
./mvnw test -Dtest=ClassName#method  # Single test

# Local Kafka (repo root)
docker compose up -d

# Minikube — CRITICAL: build inside Minikube's Docker daemon
eval $(minikube docker-env)
docker build -t kube-train-api:vN .
kubectl apply -f k8s/deployment.yaml   # imagePullPolicy: Never

# Kubernetes order for Postgres (order matters)
kubectl apply -f k8s/postgres-storage.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml
kubectl apply -f k8s/rbac.yaml          # must precede Deployment (SA reference)
kubectl apply -f k8s/namespace-pss.yaml
kubectl apply -f k8s/quota.yaml
kubectl apply -f k8s/deployment.yaml
```

GKE deployment is fully automated via GitHub Actions (`.github/workflows/deploy.yml`): test → build → deploy.

```bash
# Helm (F4-J2) — exécuter depuis WSL, chemin /mnt/c/DEVDIR/GITHUB/kube-train
helm lint kube-train-chart
helm template kube-train ./kube-train-chart -f kube-train-chart/values-minikube.yaml
helm upgrade --install kube-train ./kube-train-chart -f kube-train-chart/values-minikube.yaml
helm upgrade --install kube-train ./kube-train-chart -f kube-train-chart/values-gke.yaml --set image.tag=$SHA --atomic
helm history kube-train
helm uninstall kube-train
```

## Architecture

### Services
- **`kube-train-api/`** — Java 21 / Spring Boot 4, Maven. REST endpoints: `GET /`, `GET /trains`, `GET /trains/{id}`, `POST /reservations`, `GET /reservations/{id}`, `GET /secure`. PostgreSQL via `TrainService`. Publishes `ReservationEvent` to Kafka (local) or Pub/Sub (GKE).
- **`train-notification-service/`** — Java 21 / Spring Boot 4 consumer. Topic `train-reservations`, sends simulated email, idempotent (ConcurrentHashMap), DLT support. Consumer group `notification-dlt-group`.
- Root `pom.xml` is the parent; both services are Maven children.

### Kubernetes manifests (`k8s/`)
Flat files — no Helm/Kustomize yet (F4-J2 goal is to create the chart).
- `deployment.yaml` — Minikube (`imagePullPolicy: Never`)
- `deployment-gke.yaml` — GKE (git SHA via `IMAGE_TAG_PLACEHOLDER`)
- `notification-deployment-gke.yaml` — notification service on GKE
- `rbac.yaml` / `rbac-gke.yaml` — ServiceAccount `kube-train-api-sa` + Role/RoleBinding (F4-J1)
- `namespace-pss.yaml` — PSS labels (enforce: baseline, audit/warn: restricted)
- `quota.yaml` — LimitRange + ResourceQuota

### Spring profiles & messaging
- Profile `"gcp"` → Pub/Sub; `"!gcp"` → Kafka. GKE uses `"postgres,gcp"`.
- Kafka local is disabled by default; enabled via `KAFKA_ENABLED=true` (Docker Compose sets this).
- Config via `@Value("${train.*}")` injected in `TrainController`.

### Non-obvious wiring
- ConfigMap/Secret keys use `SCREAMING_SNAKE_CASE`; Spring relaxed binding maps them to `train.*` properties.
- `API_KEY` must be re-exported as `TRAIN_API_KEY` in `deployment-gke.yaml` via `secretKeyRef` — it does NOT auto-map.
- All health probes hit `/actuator/health`. `startupProbe` is essential — Spring Boot 4 takes ~38s on GKE Autopilot.
- Both Dockerfiles use `eclipse-temurin:21-jre-jammy` — never alpine (gRPC native crash with Cloud SQL Auth Proxy).

## GCP infrastructure

- **Project**: `kube-train-project` (number: `399291708401`)
- **GKE**: `kube-train-cluster`, `europe-west1`, Autopilot
- **Cloud SQL**: `kube-train-db`, PostgreSQL 15, `db-f1-micro`, `europe-west1-d`. Connection via Cloud SQL Auth Proxy sidecar.
- **Artifact Registry**: `europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/`
- **Pub/Sub**: topics `train-reservations` + `train-reservations-dlq`, subscription `notification-subscription`
- **nginx-ingress**: IP `34.78.39.236`, host `api.34.78.39.236.nip.io`

Budget cible : ≤ 5€/jour. Stratégie : `terraform destroy` en fin de journée, stopper Cloud SQL entre sessions.

## Formation F4 — état d'avancement

| Jour | Sujet | Statut |
|------|-------|--------|
| **J1** | Sécurité K8s & RBAC (securityContext, PSS, SA dédiés, LimitRange, init containers) | ✅ Complété |
| **J2** | Helm & Packaging (chart custom, templating, values multi-env, Jobs/CronJobs, helm --atomic) | ✅ Complété |
| **J3** | Terraform IaC GCP (provider, backend GCS, Workload Identity, pipeline PR→apply) | ✅ Complété |
| **J4** | Istio Service Mesh (mTLS, VirtualService canary, AuthorizationPolicy, fault injection) | ✅ Complété |
| J5 | SRE pratique (SLO API Cloud Monitoring, burn rate alertes, dashboards MQL, Gatekeeper) | — |

### Conventions de naming F4
- DLT Kafka Spring 4.x : `{topic}-dlt` (hyphen, lowercase). Before 4.x: `{topic}.DLT`.
- Pod selector label: `app: kube-train-pod` (Deployment label: `app: kube-train`). Service sélectionne sur `kube-train-pod`.
- Image tags Minikube: entiers manuels (`v1`, `v2`…). GKE: git SHA via CI.
- Commentaires et notes YAML/docs en français.

### Docs de formation
- `docs/4-formation-platform-engineering/formation-platform-engineering-plan.md` — plan complet F4
- `docs/4-formation-platform-engineering/runbook.md` — index runbook (prérequis communs + debug) ; commandes par jour dans `Jx-*/runbook-Jx.md`
- `docs/4-formation-platform-engineering/bilan.md` — bilan de fin de formation F4 (scores, livrables, re-check 12 facteurs)
- `docs/4-formation-platform-engineering/Jx-*/notes-Jx.md` — notes de révision
- `docs/4-formation-platform-engineering/Jx-*/qcm-Jx.md` — QCM 8 questions fin de journée
- `docs/4-formation-platform-engineering/Jx-*/tp-Jx-*.md` — TP en 4-5 étapes progressives
- `docs/readme.md` — roadmap complète + cheat-sheet CLI (consulter avant de suggérer des commandes)
