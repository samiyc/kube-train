# Kube-Train — Copilot Instructions

Hands-on Kubernetes/cloud-native training project (5-day program). A Spring Boot 4 API (`kube-train-api/`) and a notification micro-service (`train-notification-service/`) are deployed on GKE Autopilot via GitHub Actions CI/CD. Local development uses Minikube + Docker Compose.

## Repository layout

- `kube-train-api/` — Java 21 / Spring Boot 4 REST API (Maven). Endpoints: `GET /`, `GET /trains`, `GET /trains/{id}`, `POST /reservations`, `GET /reservations/{id}`, `GET /secure`. Business logic in `TrainService` (Cloud SQL PostgreSQL). Publishes `ReservationEvent` to Kafka (local) or Pub/Sub (GKE profile "gcp").
- `train-notification-service/` — Java 21 / Spring Boot 4 consumer. Listens on Kafka (local) or Pub/Sub (GKE profile "gcp") topic `train-reservations`, sends simulated email. Has idempotence (ConcurrentHashMap) and DLT (`train-reservations-dlt` / `train-reservations-dlq` on Pub/Sub) with dedicated consumer group `notification-dlt-group`.
- `k8s/` — All Kubernetes manifests (flat files, no Helm/Kustomize). Deployment files: `deployment.yaml` (Minikube, `imagePullPolicy: Never`), `deployment-gke.yaml` (API on GKE, `IMAGE_TAG_PLACEHOLDER` replaced by pipeline), and `notification-deployment-gke.yaml` (notification service on GKE). GKE also has `ingress-gke.yaml` (HTTPS nip.io, cert-manager) and `cluster-issuer.yaml` (Let's Encrypt, setup one-time manual).
- `docker-compose.yml` — Kafka KRaft (no Zookeeper), single-node, port 9092. Used for local dev of both services.
- `locustfile.py` — Load test against `/` and `/reserver` (weights 3:1).
- `docs/readme.md` — Course roadmap and canonical cheat-sheet. Consult before suggesting CLI workflows.
- `docs/1-formation-kubernetes-minikube/` — `runbook.md` (Minikube deployment runbook), `formation-minikube-plan.md` (Kubernetes/Minikube training plan).
- `docs/2-formation-cloud-native/` — `runbook.md` (GCP deployment runbook), `formation-cloud-native-notes.md` (revision notes: J1–J5), `formation-cloud-native-plan.md` (5-day training plan and architecture diagrams), `qcm-fin-formation/` (end-of-training quizzes).
- `docs/3-formation-cloud-native-beyond/` — `runbook.md` (ops quotidiennes GCP + setup F3 par journée), `formation-cn-beyond-notes.md` (revision notes), `formation-cn-beyond-plan.md` (5-day plan), `qcm/` (QCMs par journée), `extra/` (Terraform vs Ansible, outils révision, roadmap certifs, tips GCP).
- `docs/4-formation-platform-engineering/` — `formation-platform-engineering-plan.md` (5-day plan: Sécurité K8s/RBAC, Helm, Terraform, Istio, SRE), `runbook.md` (procédures ops). Organisé par jour : `J1-securite-rbac/`, `J2-helm-packaging/`, `J3-terraform-iac/`, `J4-istio-mesh/`, `J5-sre-observabilite/` — chaque dossier contient `notes-Jx.md`, `qcm-Jx.md`, `tp-Jx-*.md`, `corrections/`.

## Build & run

**GKE (CI/CD)** — GitHub Actions pipeline (`.github/workflows/deploy.yml`), 3 jobs: test → build → deploy:
1. **test** — Maven tests for both services
2. **build** — Docker build + push to Artifact Registry (`europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api`). Both Dockerfiles use `eclipse-temurin:21-jre-jammy` (NOT alpine — gRPC native crash with Cloud SQL Auth Proxy).
3. **deploy** — Checks cluster existence (skips gracefully if cluster deleted), syncs K8s secret from GCP Secret Manager (upsert pattern), annotates ServiceAccount for Workload Identity (`kubectl annotate serviceaccount`), `kubectl apply` all manifests with `sed` image tag substitution, checks if nginx-ingress is installed before applying Ingress, deploys Ingress HTTPS (IP resolved dynamically → `api.<IP>.nip.io`).

**Minikube (local)** — image built inside Minikube's Docker daemon:
```bash
eval $(minikube docker-env)        # CRUCIAL: target Minikube's Docker
docker build -t kube-train-api:vN .
kubectl apply -f k8s/deployment.yaml   # imagePullPolicy: Never
```

**Local Maven**: `./mvnw spring-boot:run` from `kube-train-api/` or `train-notification-service/`.
**Local Kafka**: `docker compose up -d` from repo root.

## Tests

- Full suite: `./mvnw test` (in `kube-train-api/`). 36 tests (dont 7 BDD Cucumber), BUILD SUCCESS verified.
- Single test: `./mvnw test -Dtest=ClassName#method`.
- The Dockerfile builds with `-DskipTests`; run tests separately.

## Spring profiles & messaging

- **Profile strategy**: `"gcp"` activates Pub/Sub, `"!gcp"` activates Kafka. GKE uses `"postgres,gcp"`.
- **Pub/Sub** (GKE): topics `train-reservations` + `train-reservations-dlq`, subscription `notification-subscription`.
- **Kafka** (local/Docker): topic `train-reservations`, DLT `train-reservations-dlt`. Conditional activation: `@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")`. Local default: disabled (NoOp publisher). Docker: `KAFKA_ENABLED=true`.

## Spring ↔ Kubernetes wiring (non-obvious)

- Config values injected via `@Value("${train.*}")` in `TrainController`.
- ConfigMap/Secret keys use **SCREAMING_SNAKE_CASE** (`TRAIN_MESSAGE`, `API_KEY`) mapped by Spring Boot relaxed binding.
- `API_KEY` does **not** auto-map to `train.api.key` — `deployment-gke.yaml` re-exports it explicitly as `TRAIN_API_KEY` via `secretKeyRef`. Follow this same pattern for any new secret.
- `kube-train-secrets` is managed by the CI/CD pipeline (reads from GCP Secret Manager, upserts K8s secret). For Minikube: `kubectl create secret generic kube-train-secrets --from-literal=API_KEY=...`.
- All three probes hit `/actuator/health`: `startupProbe` (30×5s = 150s max, blocks others), `livenessProbe` (every 10s, restart on failure), `readinessProbe` (every 5s, remove from Service on failure). The startupProbe is essential — Spring Boot 4 takes ~38s to start on GKE Autopilot.

## GCP infrastructure

- **Project**: `kube-train-project` (ID), `399291708401` (number)
- **Artifact Registry**: `europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/`
- **GKE cluster**: `kube-train-cluster`, region `europe-west1`, Autopilot mode
- **Secret Manager**: secret `api-key` — source of truth for `API_KEY`
- **Cloud SQL**: instance `kube-train-db`, PostgreSQL 15, `db-f1-micro`, `europe-west1-d`. DB: `kube_train`, user: `kube_train_user`. Connection via Cloud SQL Auth Proxy sidecar (IAM: compute SA has `roles/cloudsql.client`). ⚠️ Delete when not in use to save credits.
- **Pub/Sub**: topics `train-reservations` and `train-reservations-dlq`, subscription `notification-subscription`.
- **nginx-ingress**: LoadBalancer IP `34.78.39.236`, host `api.34.78.39.236.nip.io`
- **cert-manager**: installed via Helm (`global.leaderElection.namespace=cert-manager` — required for GKE Autopilot). ClusterIssuer `letsencrypt-prod` applied manually (email never committed).

## Conventions

- Manifests are flat files in `k8s/`, applied individually. Order matters for Postgres: `postgres-storage.yaml` → `postgres-deployment.yaml` → `postgres-service.yaml`.
- Pod selector label is `app: kube-train-pod` (Deployment label is `app: kube-train`). Service selects on `kube-train-pod`.
- Image tags for Minikube are manual integers (`v1`, `v2`, …). For GKE, the pipeline uses git SHA.
- GKE Ingress host: `api.34.78.39.236.nip.io`. Minikube Ingress host: `api.kube-train.local` (test with `-H "Host: api.kube-train.local"`).
- Comments and commit notes in YAML/README are in French.
- Kafka DLT naming in Spring Kafka 4.x: `{topic}-dlt` (hyphen+lowercase). Before 4.x it was `{topic}.DLT`.
- Both Dockerfiles use `eclipse-temurin:21-jre-jammy` — do NOT use alpine (gRPC native library crash with Cloud SQL Auth Proxy).
- Multi-module project: root `pom.xml` is the parent, `kube-train-api/pom.xml` and `train-notification-service/pom.xml` are children.

## Formation F4 — Platform Engineering & Certifications

Formation en cours (5 jours). Objectif : production-readiness + préparation CKAD / GCP DevOps Engineer.

- **J1** — Sécurité K8s & RBAC (securityContext, PSS, ServiceAccounts dédiés, LimitRange, init containers)
- **J2** — Helm & Packaging (chart custom, templating, values multi-env, Jobs/CronJobs, ArgoCD+Helm)
- **J3** — Terraform IaC GCP (provider google, backend GCS, import, Workload Identity, pipeline PR→plan→apply)
- **J4** — Istio Service Mesh (mTLS, VirtualService canary, AuthorizationPolicy, fault injection)
- **J5** — SRE pratique (SLO API Cloud Monitoring, burn rate alertes, dashboards golden signals, OPA/Gatekeeper)

Budget cible : ≤ 5€/jour GCP (132€ crédits restants). Stratégie : `terraform destroy` en fin de journée.
