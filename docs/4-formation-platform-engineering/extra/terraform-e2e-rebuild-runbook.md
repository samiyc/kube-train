# Runbook — Audit statique Terraform & rebuild E2E GKE

> Formation F4 — Platform Engineering  
> Objectif : vérifier les chemins après réorganisation `k8s/`, puis fournir une procédure **safe** pour reconstruire l'infra GKE via Terraform, redéployer, smoke-tester et détruire.  
> ⚠️ Ce document est un runbook à exécuter manuellement dans WSL. L'audit ci-dessous est statique : aucune commande cloud n'a été lancée.

---

## 1. Verdict rapide

| Zone auditée | Verdict | Détail |
|---|---|---|
| Pipeline GitHub Actions | ✅ OK | Tous les chemins `k8s/...` référencés dans `.github/workflows/deploy.yml` existent. |
| Réorganisation `k8s/` | ✅ OK côté CI/CD | Le pipeline utilise bien les sous-dossiers `workloads/`, `security/`, `network/`, `observability/`. |
| Docs historiques | ✅ Corrigé (2026-07-10) | Les anciens chemins plats des docs F2/F3 (`k8s/deployment.yaml`, `k8s/otel-collector.yaml`, etc.) ont été remappés vers les sous-dossiers (`k8s/workloads/`, `k8s/observability/`, `k8s/network/`). Voir §3.4. |
| Terraform | ✅ Infra only | Aucun provider Kubernetes/Helm, aucun `kubectl`, aucun manifest K8s appliqué par Terraform. |
| Rebuild from scratch | ⚠️ À préparer | Le backend GCS, les secrets Secret Manager, le Workload Identity GitHub et certaines APIs/rôles sont supposés déjà existants ou gérés hors Terraform. |

---

## 2. Audit Terraform — `infra/`

### 2.1 Fichiers lus

| Fichier | Rôle |
|---|---|
| `infra/versions.tf` | Version Terraform, provider Google, backend d'état. |
| `infra/providers.tf` | Configuration du provider Google. |
| `infra/variables.tf` | Variables `project_id`, `region`, `cluster_name`. |
| `infra/database.tf` | Cloud SQL PostgreSQL + DB + user. |
| `infra/gke.tf` | Cluster GKE Autopilot. |
| `infra/messaging.tf` | Artifact Registry + Pub/Sub topics/subscription. |
| `infra/iam.tf` | Service Accounts et IAM bindings. |
| `infra/apis.tf` | APIs GCP liées au service mesh / network services. |
| `infra/main.tf` | Vide. |
| `infra/.gitignore` | Ignore `.terraform/`, états locaux et `*.tfvars`. |
| `infra/.terraform.lock.hcl` | Provider Google locké en `6.50.0`. |
| `infra/terraform.tfvars` | Local, non tracké : `db_password = "root"`. |

### 2.2 Backend, versions et variables

| Élément | Valeur auditée |
|---|---|
| Terraform | `required_version = ">= 1.8.0"` |
| Provider | `hashicorp/google ~> 6.0`, lock actuel `6.50.0` |
| Backend | `gcs`, bucket `kube-train-terraform-state`, prefix `platform-engineering/dev` |
| Projet par défaut | `kube-train-project` |
| Région par défaut | `europe-west1` |
| Cluster par défaut | `kube-train-cluster` |
| Variable obligatoire | `db_password` (`sensitive = true`) |

⚠️ Le backend GCS n'est pas provisionné dans ce module : le bucket d'état doit exister avant `terraform init`.

### 2.3 Infra provisionnée

| Domaine | Ressources Terraform |
|---|---|
| GKE | `google_container_cluster.main` en Autopilot, région `europe-west1`, Workload Identity activé, réseau/subnet `default`, `deletion_protection = false`. |
| Cloud SQL | Instance `kube-train-db` PostgreSQL 15, tier `db-f1-micro`, backups activés, IPv4 activé, `deletion_protection = false`, DB `kube_train`, user `kube_train_user`. |
| Messaging | Artifact Registry Docker `kube-train-repo`, topics Pub/Sub `train-reservations` et `train-reservations-dlq`, subscription `notification-subscription` avec dead-letter policy. |
| IAM | GSA `kube-train-api-sa`, GSA `notification-sa`, rôles Cloud SQL / Secret Manager / Pub/Sub, bindings Workload Identity vers les KSA `default/kube-train-api-sa` et `default/notification-sa`, rôles pour `github-actions-sa`. |
| APIs | `trafficdirector.googleapis.com`, `meshca.googleapis.com`, `networksecurity.googleapis.com`, `networkservices.googleapis.com`. |

### 2.4 Ce que Terraform ne fait pas

Terraform provisionne **l'infra seulement** :

- pas de provider `kubernetes` ;
- pas de provider `helm` ;
- pas de `kubectl apply` ;
- pas de sync de Secret Kubernetes ;
- pas de déploiement applicatif ;
- pas d'installation `nginx-ingress`, `cert-manager`, Gatekeeper ou Istio.

### 2.5 Points d'attention Terraform

| Point | Risque | Action recommandée |
|---|---|---|
| `terraform.tfvars` local contient `db_password = "root"` | Mot de passe faible si réutilisé en GKE | Remplacer localement par une valeur forte et garder le fichier non commité. |
| Backend GCS externe | `terraform init` échoue si le bucket n'existe plus | Vérifier/créer le bucket d'état avant rebuild. |
| APIs incomplètes pour un projet vierge | `container`, `sqladmin`, `pubsub`, `artifactregistry`, `secretmanager`, `cloudtrace`, `monitoring` ne sont pas déclarées ici | Sur projet neuf, activer ces APIs manuellement ou étendre `apis.tf`. |
| Secret Manager hors Terraform | Le pipeline lit `api-key`, `db-username`, `db-password` | Recréer/vérifier ces secrets avant déploiement. |
| WIF GitHub hors Terraform | Le workflow utilise un pool/provider et `github-actions-sa` préexistants | Rebuild complet du projet ≠ couvert par ce module. |
| Artifact Registry à nom fixe | `apply` peut échouer si le repo existe déjà hors state | Importer ou supprimer proprement avant apply. |
| Cloud SQL `ipv4_enabled = true` | IP publique présente même si l'app passe par Cloud SQL Auth Proxy | À accepter pour la formation ou durcir plus tard. |
| Pub/Sub DLQ | La subscription référence la DLQ, mais aucun binding du service agent Pub/Sub vers le topic DLQ n'est visible | Vérifier si le dead-letter forwarding fonctionne après rebuild. |
| `rbac-gke.yaml` vs Terraform | Le YAML annote encore vers le compute SA, puis le pipeline overwrites vers les GSA dédiés | En déploiement manuel, annoter explicitement vers les GSA Terraform. |
| OTel IAM | Notes J5 indiquent besoin de `roles/cloudtrace.agent` et `roles/monitoring.metricWriter` | Vérifier les rôles du SA utilisé par `otel-collector` après rebuild. |

---

## 3. Audit chemins `k8s/`

### 3.1 Arborescence réelle

| Dossier | Fichiers |
|---|---|
| `k8s/workloads/` | `configmap.yaml`, `deployment.yaml`, `deployment-gke.yaml`, `deployment-gke-v2.yaml`, `hpa.yaml`, `notification-deployment-gke.yaml`, `notification-service.yaml`, `service.yaml` |
| `k8s/security/` | `gatekeeper-ct-allowed-repos.yaml`, `gatekeeper-ct-required-limits.yaml`, `namespace-pss.yaml`, `quota.yaml`, `rbac-gke.yaml`, `rbac.yaml` |
| `k8s/network/` | `cluster-issuer.yaml`, `ingress-gke.yaml`, `ingress.yaml`, `network-policy-api.yaml`, `network-policy-default-deny.yaml`, `network-policy-gmp.yaml`, `network-policy-notification.yaml`, `network-policy-otel-scraping.yaml`, `network-policy-otel.yaml` |
| `k8s/observability/` | `otel-collector.yaml`, `pod-monitoring.yaml`, `servicemonitor.yaml` |
| `k8s/database/` | `postgres-deployment.yaml`, `postgres-service.yaml`, `postgres-storage.yaml` |
| `k8s/istio/` | `authorization-policy.yaml`, `istio-canary.yaml`, `istio-fault-injection.yaml`, `istio-test-pods.yaml`, `peer-authentication-strict.yaml` |
| `k8s/argocd/` | `application.yaml` |

### 3.2 Chemins référencés par le pipeline

| Chemin référencé | Existe ? | Notes |
|---|---:|---|
| `k8s/security/rbac-gke.yaml` | ✅ | Appliqué avant annotation Workload Identity. |
| `k8s/workloads/deployment-gke.yaml` | ✅ | Utilisé avec `sed ... | kubectl apply -f -`. |
| `k8s/workloads/configmap.yaml` | ✅ | Appliqué par le job deploy. |
| `k8s/workloads/service.yaml` | ✅ | Service `LoadBalancer`. |
| `k8s/workloads/hpa.yaml` | ✅ | HPA API. |
| `k8s/workloads/notification-deployment-gke.yaml` | ✅ | Utilisé avec `sed ... | kubectl apply -f -`. |
| `k8s/observability/otel-collector.yaml` | ✅ | Collector OTLP + Prometheus receiver. |
| `k8s/network/network-policy-default-deny.yaml` | ✅ | Default deny ingress. |
| `k8s/network/network-policy-api.yaml` | ✅ | Allow API ingress. |
| `k8s/network/network-policy-otel.yaml` | ✅ | Allow OTLP vers collector. |
| `k8s/network/ingress-gke.yaml` | ✅ | Appliqué deux fois : deploy direct puis step conditionnel nginx. |
| `k8s/workloads/deployment-gke-v2.yaml` | ✅ | Mis à jour par le job GitOps `update-manifests`, pas déployé par le job deploy standard. |

✅ Verdict CI/CD : aucun chemin cassé dans `.github/workflows/deploy.yml`.

### 3.3 Manifests présents mais non appliqués par le pipeline

| Manifest | Statut attendu |
|---|---|
| `k8s/workloads/deployment.yaml` | Minikube uniquement. |
| `k8s/workloads/deployment-gke-v2.yaml` | Canary Istio manuel / GitOps tag update seulement. |
| `k8s/workloads/notification-service.yaml` | Service ClusterIP notification pour tests Istio mTLS. |
| `k8s/security/rbac.yaml` | Minikube / RBAC local. |
| `k8s/security/namespace-pss.yaml` | À appliquer manuellement si PSS doit être réinstallé. |
| `k8s/security/quota.yaml` | À appliquer manuellement si quota/LimitRange voulus après rebuild. |
| `k8s/security/gatekeeper-ct-*.yaml` | J5 Gatekeeper manuel. Nécessite Gatekeeper installé. |
| `k8s/database/postgres-*.yaml` | Postgres in-cluster local/Minikube ; à ne pas appliquer pour GKE Cloud SQL sauf test volontaire. |
| `k8s/network/cluster-issuer.yaml` | Cert-manager manuel ; contient l'issuer Let's Encrypt. |
| `k8s/network/ingress.yaml` | Ingress Minikube. |
| `k8s/network/network-policy-notification.yaml` | J4 Istio / notification mTLS. |
| `k8s/network/network-policy-gmp.yaml` | J5 GMP ; attention namespace `gke-gmp-system`. |
| `k8s/network/network-policy-otel-scraping.yaml` | J5 OTel scraping. |
| `k8s/observability/pod-monitoring.yaml` | J5 GMP, non utilisé par le pipeline. |
| `k8s/observability/servicemonitor.yaml` | Prometheus Operator local/legacy, non appliqué par le pipeline. |
| `k8s/istio/*.yaml` | J4 Istio manuel : mTLS, canary, authz, fault injection, test pods. |
| `k8s/argocd/application.yaml` | GitOps dormant ; ArgoCD supprimé pour budget. |

### 3.4 Références docs stale détectées — ✅ corrigées le 2026-07-10

> Les 13 références ci-dessous ont été remappées vers les sous-dossiers `k8s/` (consolidation pré-F5). Table conservée pour traçabilité.

| Fichier | Référence stale | Remplacement appliqué |
|---|---|---|
| `docs/2-formation-cloud-native/formation-cloud-native-notes.md` | `k8s/servicemonitor.yaml` | `k8s/observability/servicemonitor.yaml` |
| `docs/2-formation-cloud-native/runbook.md` | `k8s/service.yaml` | `k8s/workloads/service.yaml` |
| `docs/2-formation-cloud-native/runbook.md` | `k8s/servicemonitor.yaml` | `k8s/observability/servicemonitor.yaml` |
| `docs/2-formation-cloud-native/runbook.md` | `k8s/deployment.yaml` | `k8s/workloads/deployment.yaml` |
| `docs/2-formation-cloud-native/runbook.md` | `k8s/cluster-issuer.yaml` | `k8s/network/cluster-issuer.yaml` |
| `docs/3-formation-cloud-native-beyond/formation-cn-beyond-notes.md` | `k8s/otel-collector.yaml` | `k8s/observability/otel-collector.yaml` |
| `docs/3-formation-cloud-native-beyond/formation-cn-beyond-plan.md` | `k8s/otel-collector.yaml` | `k8s/observability/otel-collector.yaml` |
| `docs/3-formation-cloud-native-beyond/formation-cn-beyond-plan.md` | `k8s/network-policy-default-deny.yaml` | `k8s/network/network-policy-default-deny.yaml` |
| `docs/3-formation-cloud-native-beyond/formation-cn-beyond-plan.md` | `k8s/network-policy-api.yaml` | `k8s/network/network-policy-api.yaml` |
| `docs/3-formation-cloud-native-beyond/formation-cn-beyond-plan.md` | `k8s/network-policy-notification.yaml` | `k8s/network/network-policy-notification.yaml` |
| `docs/3-formation-cloud-native-beyond/qcm/corrections/correction-qcm-J3-27-05-2026.md` | `k8s/otel-collector.yaml` | `k8s/observability/otel-collector.yaml` |
| `docs/3-formation-cloud-native-beyond/qcm/corrections/correction-qcm-J4-28-05-2026.md` | `k8s/network-policy-api.yaml` | `k8s/network/network-policy-api.yaml` |

Note : `docs/3-formation-cloud-native-beyond/qcm/qcm-J3.md` mentionnait `k8s/network-policy-deny.yaml` dans une question hypothétique ; remappé aussi en `k8s/network/network-policy-deny.yaml` par cohérence.

### 3.5 Helm chart

Le chart `kube-train-chart/` ne référence pas d'anciens chemins `k8s/*.yaml`, sauf un commentaire indiquant que le ServiceAccount est créé hors chart par `k8s/security/rbac.yaml` — chemin valide.

⚠️ Point fonctionnel hors path-audit : `templates/deployment.yaml` contient toujours un initContainer `wait-for-postgres` qui attend `postgres-service:5432`. Avec `values-gke.yaml`, le chart active Cloud SQL Proxy mais ne désactive pas cet initContainer. Si le chart est utilisé sur GKE sans Postgres in-cluster, ce point peut bloquer le pod.

---

## 4. Runbook rebuild E2E GKE — à exécuter dans WSL

### Prérequis

```bash
# Depuis WSL
cd /mnt/c/DEVDIR/GITHUB/kube-train

gcloud auth login
gcloud config set project kube-train-project
gcloud auth application-default login

terraform -version    # >= 1.8
kubectl version --client
gcloud config get-value project
```

Vérifier les prérequis hors Terraform :

```bash
# Backend Terraform
gsutil ls gs://kube-train-terraform-state

# Secrets attendus par le pipeline et le déploiement manuel
gcloud secrets describe api-key --project=kube-train-project
gcloud secrets describe db-username --project=kube-train-project
gcloud secrets describe db-password --project=kube-train-project
```

Préparer `infra/terraform.tfvars` localement si absent :

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra
cat > terraform.tfvars <<'EOF'
db_password = "REMPLACER_PAR_UN_MOT_DE_PASSE_FORT"
EOF
```

---

## Étape 0 — validation statique Terraform

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra

terraform fmt -check -recursive
terraform init
terraform validate
terraform plan
```

À vérifier dans le plan :

- création ou conservation de `google_container_cluster.main` ;
- `deletion_protection = false` sur GKE et Cloud SQL ;
- aucun provider Kubernetes/Helm ;
- aucune ressource inattendue hors budget ;
- pas de recréation accidentelle d'une ressource importée manuellement.

---

## Étape 1 — rebuild infra

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra
terraform apply
```

Notes :

- GKE Autopilot peut prendre environ **10 minutes**.
- Cloud SQL peut prendre plusieurs minutes.
- Si `Artifact Registry` ou `Cloud SQL` existent déjà hors state, importer ou supprimer proprement avant de relancer.

Configurer `kubectl` :

```bash
gcloud container clusters get-credentials kube-train-cluster \
  --region=europe-west1 \
  --project=kube-train-project

kubectl get nodes
```

---

## Étape 2 — déploiement applicatif

### Option A — pipeline GitHub Actions

Option recommandée si le cluster existe et que les images doivent être rebuildées :

```bash
git status
git push origin main
```

Ou relancer le workflow depuis GitHub Actions : **CI/CD — Build & Deploy to GKE**.

Le pipeline :

1. teste les deux services Maven ;
2. build/push les images ;
3. vérifie l'existence du cluster ;
4. applique `k8s/security/rbac-gke.yaml` ;
5. annote les KSA pour Workload Identity ;
6. synchronise `kube-train-secrets` depuis Secret Manager ;
7. applique workloads, observability, network policies et ingress.

### Option B — déploiement manuel contrôlé

Définir les images :

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train

SHA=$(git rev-parse --short=40 HEAD)
IMAGE="europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api:${SHA}"
IMAGE_NOTIFICATION="europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/train-notification-service:${SHA}"
```

Si les images n'existent pas encore dans Artifact Registry, utiliser le pipeline ou builder/pusher explicitement avant `kubectl apply`.

Ordre GKE recommandé :

```bash
# 1) RBAC / ServiceAccounts d'abord
kubectl apply -f k8s/security/rbac-gke.yaml

# 2) Workload Identity explicite vers les GSA créés par Terraform
kubectl annotate serviceaccount kube-train-api-sa \
  iam.gke.io/gcp-service-account=kube-train-api-sa@kube-train-project.iam.gserviceaccount.com \
  --namespace=default \
  --overwrite

kubectl annotate serviceaccount notification-sa \
  iam.gke.io/gcp-service-account=notification-sa@kube-train-project.iam.gserviceaccount.com \
  --namespace=default \
  --overwrite

# 3) PSS + quotas si souhaités après rebuild
kubectl apply -f k8s/security/namespace-pss.yaml
kubectl apply -f k8s/security/quota.yaml

# 4) Secret Kubernetes depuis Secret Manager — pattern upsert identique au pipeline
API_KEY=$(gcloud secrets versions access latest --secret=api-key --project=kube-train-project)
DB_USERNAME=$(gcloud secrets versions access latest --secret=db-username --project=kube-train-project)
DB_PASSWORD=$(gcloud secrets versions access latest --secret=db-password --project=kube-train-project)

kubectl create secret generic kube-train-secrets \
  --from-literal=API_KEY="${API_KEY}" \
  --from-literal=DB_USERNAME="${DB_USERNAME}" \
  --from-literal=DB_PASSWORD="${DB_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

# 5) Config et service avant les workloads
kubectl apply -f k8s/workloads/configmap.yaml
kubectl apply -f k8s/workloads/service.yaml

# 6) Workloads avec remplacement d'image
sed "s|image: .*kube-train-api.*|image: ${IMAGE}|g" \
  k8s/workloads/deployment-gke.yaml | kubectl apply -f -

sed "s|image: .*train-notification-service.*|image: ${IMAGE_NOTIFICATION}|g" \
  k8s/workloads/notification-deployment-gke.yaml | kubectl apply -f -

# 7) Autoscaling et observability
kubectl apply -f k8s/workloads/hpa.yaml
kubectl apply -f k8s/observability/otel-collector.yaml

# 8) NetworkPolicies
kubectl apply -f k8s/network/network-policy-default-deny.yaml
kubectl apply -f k8s/network/network-policy-api.yaml
kubectl apply -f k8s/network/network-policy-otel.yaml

# 9) Ingress seulement si nginx-ingress + cert-manager + ClusterIssuer sont installés
kubectl get namespace ingress-nginx
kubectl get clusterissuer letsencrypt-prod
kubectl apply -f k8s/network/ingress-gke.yaml
```

Cas Postgres in-cluster (Minikube/local uniquement, pas le chemin GKE Cloud SQL) :

```bash
kubectl apply -f k8s/database/postgres-storage.yaml
kubectl apply -f k8s/database/postgres-deployment.yaml
kubectl apply -f k8s/database/postgres-service.yaml
kubectl apply -f k8s/security/rbac.yaml
kubectl apply -f k8s/security/namespace-pss.yaml
kubectl apply -f k8s/security/quota.yaml
kubectl apply -f k8s/workloads/configmap.yaml
kubectl apply -f k8s/workloads/service.yaml
kubectl apply -f k8s/workloads/deployment.yaml
```

Vérifier les rollouts :

```bash
kubectl rollout status deployment/kube-train-deployment --timeout=5m
kubectl rollout status deployment/notification-deployment --timeout=5m
kubectl rollout status deployment/otel-collector --timeout=3m

kubectl get pods -o wide
kubectl get svc
```

---

## Étape 3 — smoke test E2E

### 3.1 Endpoints API

Avec le Service `LoadBalancer` :

```bash
LB_IP=$(kubectl get svc kube-train-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "LB_IP=${LB_IP}"

curl -i "http://${LB_IP}/"
curl -i "http://${LB_IP}/trains"
curl -i "http://${LB_IP}/trains/1"
curl -i "http://${LB_IP}/actuator/health"
```

Avec l'Ingress HTTPS, si `nginx-ingress` est réinstallé :

```bash
INGRESS_HOST="api.34.78.39.236.nip.io"
curl -i "https://${INGRESS_HOST}/"
curl -i "https://${INGRESS_HOST}/trains"
```

⚠️ Si l'IP du LoadBalancer a changé après rebuild, mettre à jour `k8s/network/ingress-gke.yaml` (`api.<IP>.nip.io`) avant d'appliquer l'Ingress.

### 3.2 Réservation + notification

```bash
API_KEY=$(gcloud secrets versions access latest --secret=api-key --project=kube-train-project)

curl -i -X POST "http://${LB_IP}/reservations" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: ${API_KEY}" \
  -d '{
    "trainId": 1,
    "passengerName": "Smoke Test",
    "passengerEmail": "smoke@example.com"
  }'
```

Noter l'id retourné, puis :

```bash
RESERVATION_ID="<id-retourne>"
curl -i "http://${LB_IP}/reservations/${RESERVATION_ID}" \
  -H "X-API-KEY: ${API_KEY}"
```

Vérifier que le consumer notification reçoit l'événement :

```bash
NOTIF_POD=$(kubectl get pod -l app=notification-pod -o jsonpath='{.items[0].metadata.name}')
kubectl logs "${NOTIF_POD}" -c notification-container --tail=100
```

### 3.3 Endpoint sécurisé

```bash
curl -i "http://${LB_IP}/secure"

curl -i "http://${LB_IP}/secure" \
  -H "X-API-KEY: ${API_KEY}"
```

Le premier appel doit être refusé, le second doit passer.

### 3.4 Debug pods multi-containers

```bash
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'

API_POD=$(kubectl get pod -l app=kube-train-pod -o jsonpath='{.items[0].metadata.name}')
kubectl logs "${API_POD}" -c api-container --tail=80
kubectl logs "${API_POD}" -c cloud-sql-proxy --tail=80
```

---

## Étape 4 — teardown budget

Objectif budget : ≤ 5 €/jour. Détruire dès la fin de session.

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra
terraform destroy
```

Si vous ne détruisez pas immédiatement mais voulez limiter la dépense Cloud SQL :

```bash
gcloud sql instances patch kube-train-db \
  --activation-policy=NEVER \
  --project=kube-train-project
```

Après destroy :

```bash
gcloud container clusters describe kube-train-cluster \
  --region=europe-west1 \
  --project=kube-train-project

gcloud sql instances describe kube-train-db \
  --project=kube-train-project
```

Ces deux commandes doivent échouer si les ressources ont bien été détruites.

---

## 5. Pièges connus pour un rebuild E2E

| Piège | Symptôme | Fix |
|---|---|---|
| Workload Identity manquant | Cloud SQL / Pub/Sub / OTel en `PERMISSION_DENIED` | Annoter les KSA et vérifier les bindings `roles/iam.workloadIdentityUser`. |
| IP LoadBalancer changée | `api.34.78.39.236.nip.io` ne pointe plus vers le bon LB | `kubectl get svc kube-train-service`, puis mettre à jour `ingress-gke.yaml`. |
| `nginx-ingress` absent | Le step Ingress est ignoré ou l'Ingress reste sans routage | Réinstaller nginx-ingress avant `kubectl apply -f k8s/network/ingress-gke.yaml`. |
| `cert-manager` / `ClusterIssuer` absent | TLS non provisionné | Réinstaller cert-manager et appliquer `k8s/network/cluster-issuer.yaml` avec un email valide non commité. |
| Container name incorrect | `kubectl exec/logs -c kube-train-api` échoue | Utiliser `api-container`, `cloud-sql-proxy`, `notification-container`, `otel-collector`. |
| OTel permissions | `cloudtrace.traces.patch` ou `monitoring.metricDescriptors.create` refusé | Ajouter `roles/cloudtrace.agent` et `roles/monitoring.metricWriter` au SA réellement utilisé. |
| GMP namespace | NetworkPolicy GMP inefficace | Le namespace réel est `gke-gmp-system`, pas `gmp-system`. |
| Istio fault injection | Curls externes toujours `200` | Tester depuis un pod dans le mesh ; le trafic LoadBalancer externe bypasse le VirtualService. |
| Secret `API_KEY` | `/secure` refuse malgré secret présent | Vérifier que `API_KEY` est aussi exposé en `TRAIN_API_KEY` dans le Deployment. |
| Startup Spring Boot 4 + OTel | Rollout lent / startupProbe longue | Garder la startupProbe GKE à 50 × 5s. |
| Quota Autopilot | Pods `Pending` ou rollouts bloqués | Vérifier `k8s/security/quota.yaml`, HPA, canary v2 et pods de test. |

---

## 6. Conclusion d'audit

- Le chemin critique CI/CD est propre après la réorganisation `k8s/`.
- Les docs F2/F3 contenaient des références pré-réorg (chemins plats) ; **corrigées le 2026-07-10** (remappées vers les sous-dossiers `k8s/`).
- Terraform peut reconstruire l'infra principale, mais ne couvre pas tout l'écosystème opérationnel : backend GCS, secrets, WIF GitHub, ingress controller, cert-manager, Istio/Gatekeeper et certains rôles OTel restent à gérer manuellement ou via d'autres runbooks.
