# Runbook — Formation F4 Platform Engineering

> Commandes essentielles et procédures opérationnelles pour chaque journée.
> Complété au fur et à mesure de la formation.

---

## 🔧 Prérequis & setup commun

```bash
# Outils nécessaires (en plus de F3)
# Terraform
terraform version  # >= 1.7

# Helm
helm version  # >= 3.14

# Istio CLI
istioctl version

# Vérifier accès GCP
gcloud auth list
gcloud config get-value project  # kube-train-project
```

---

## 🔍 Debug — Navigation pods multi-containers (GKE Autopilot)

Sur GKE Autopilot les pods ont systématiquement plusieurs containers (`istio-proxy`, `cloud-sql-proxy`...).

```bash
# Lister les containers de tous les pods
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'

# -- Exemple de retour (f4-j5) --
# NAME                           CONTAINERS
# kube-train-deployment-1234     api-container,cloud-sql-proxy,istio-proxy
# notification-deployment-1234   notification-container,istio-proxy
# otel-collector-1234            otel-collector,istio-proxy

# Lister les containers d'un pod spécifique
kubectl get pod <pod-name> -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'

# Logs d'un container spécifique
kubectl logs <pod-name> -c <container-name>

# Logs de tous les containers (avec préfixe container — indispensable pour distinguer les lignes)
kubectl logs <pod-name> --all-containers=true --prefix=true | tail -20

# Suivre les logs en temps réel (tous containers)
kubectl logs <pod-name> --all-containers=true --prefix=true -f
```

---

## F4-J1: Sécurité Kubernetes & RBAC

```bash
# ── Prérequis image Minikube ──────────────────────────────────────────────────
eval $(minikube docker-env)                          # cibler le daemon Docker Minikube
docker build -t kube-train-api:v5 ./kube-train-api/ # construire l'image locale

# Vérifier ConfigMap + Secret (créer si absent)
kubectl get configmap kube-train-config 2>/dev/null || \
  kubectl create configmap kube-train-config --from-literal=TRAIN_MESSAGE="Bienvenue"
kubectl get secret kube-train-secrets 2>/dev/null || \
  kubectl create secret generic kube-train-secrets --from-literal=API_KEY=dev-secret-key

# Récupérer la valeur de l'API key stockée dans le secret
kubectl get secret kube-train-secrets -o jsonpath='{.data.API_KEY}' | base64 -d

# ── Minikube — apply dans l'ordre ─────────────────────────────────────────────
kubectl apply -f k8s/postgres-storage.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml
kubectl apply -f k8s/rbac.yaml              # RBAC avant Deployment (SA reference)
kubectl apply -f k8s/namespace-pss.yaml
kubectl apply -f k8s/quota.yaml
kubectl apply -f k8s/deployment.yaml
kubectl rollout status deployment/kube-train-deployment

# ── Vérifications F4-J1 ───────────────────────────────────────────────────────
kubectl get pods
kubectl logs --tail=20 deployment/kube-train-deployment -c wait-for-postgres
kubectl describe pod -l app=kube-train-pod | grep -A8 "Security Context"
kubectl get deployment kube-train-deployment -o jsonpath='{.spec.template.spec.serviceAccountName}'
# → kube-train-api-sa

# Test RBAC (can-i)
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → yes
kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → no

# PSS warnings (audit/warn=restricted)
kubectl get events --sort-by=.lastTimestamp | grep -i warning

# ResourceQuota — état actuel
kubectl describe resourcequota kube-train-quota

# Test quota : scale à 3 doit être bloqué (pods: 6, mais node-exporter+postgres = 2 déjà)
kubectl scale deployment kube-train-deployment --replicas=4
kubectl describe resourcequota kube-train-quota
kubectl scale deployment kube-train-deployment --replicas=2  # remettre à 2

# ── Tests fonctionnels ────────────────────────────────────────────────────────
kubectl port-forward svc/kube-train-service 8080:80 &
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/trains
# Récupérer la valeur API_KEY : kubectl get secret kube-train-secrets -o jsonpath='{.data.API_KEY}' | base64 -d
curl -H "X-API-KEY: <valeur_API_KEY>" http://127.0.0.1:8080/secure
curl -X POST http://127.0.0.1:8080/reservations \
  -H "Content-Type: application/json" \
  -d '{"passengerId":"Jean Dupont","trainId":"TGV-7042"}'

# ── GKE (CI/CD) ───────────────────────────────────────────────────────────────
# Le pipeline deploy.yml applique automatiquement rbac-gke.yaml
# et annote kube-train-api-sa + notification-sa pour Workload Identity.
# Vérification post-déploiement :
kubectl get serviceaccount kube-train-api-sa -o yaml
kubectl get serviceaccount notification-sa -o yaml
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa

# ── Dépannage rolling update bloqué ──────────────────────────────────────────
# Si rollout bloqué (ProgressDeadlineExceeded) :
kubectl describe deployment kube-train-deployment | tail -20
kubectl get events --sort-by=.lastTimestamp | tail -10
# Causes fréquentes : quota pods dépassé, image corrompue, init container en boucle
# Fix quota : kubectl apply -f k8s/quota.yaml (après avoir ajusté pods: N)
# Fix image : docker build --no-cache -t kube-train-api:vN ./kube-train-api/
#             puis kubectl rollout restart deployment/kube-train-deployment
```

---

## F4-J2: Helm & Packaging

> ⚠️ Toutes les commandes Helm/kubectl/minikube s'exécutent dans **WSL**.
> Chemin WSL du projet : `/mnt/c/DEVDIR/GITHUB/kube-train`

```bash
# ── Vérification pré-TP ───────────────────────────────────────────────────────
helm version                          # doit être >= 3.14
helm env                              # HELM_DATA_HOME, HELM_CACHE_HOME

# ── Étape 1 : Scaffolding ─────────────────────────────────────────────────────
cd /mnt/c/DEVDIR/GITHUB/kube-train
helm create kube-train-chart

# Nettoyage du scaffold (fichiers non utilisés dans ce TP)
rm kube-train-chart/templates/hpa.yaml
rm kube-train-chart/templates/ingress.yaml
rm kube-train-chart/templates/serviceaccount.yaml
rm -rf kube-train-chart/templates/tests/

# Vérification syntaxe du chart
helm lint kube-train-chart

# Rendu brut (sans connexion cluster)
helm template kube-train ./kube-train-chart
helm template kube-train ./kube-train-chart -s templates/deployment.yaml  # un seul template

# Dry-run complet (simule un install avec les warnings Kubernetes)
helm install kube-train ./kube-train-chart --dry-run --debug

# ── Étape 2 : Service + ConfigMap ─────────────────────────────────────────────
# Après ajout des templates service.yaml et configmap.yaml :
helm template kube-train ./kube-train-chart > /tmp/rendered.yaml
cat /tmp/rendered.yaml                # vérifier ConfigMap + Service + Deployment

# ── Étape 3 : Déploiement Minikube ────────────────────────────────────────────
eval $(minikube docker-env)
docker build -t kube-train-api:v5 ./kube-train-api/

# ConfigMap + Secret doivent exister avant helm install
kubectl get configmap kube-train-config 2>/dev/null || \
  echo "⚠️  ConfigMap manquant — sera créé par le chart"
kubectl get secret kube-train-secrets 2>/dev/null || \
  kubectl create secret generic kube-train-secrets \
    --from-literal=API_KEY=S3CR3T-K3Y-12345 \
    --from-literal=DB_USERNAME=postgres \
    --from-literal=DB_PASSWORD=postgres

# Appliquer les prérequis (RBAC, quota, PSS, postgres) qui ne sont PAS dans le chart
kubectl apply -f k8s/postgres-storage.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/namespace-pss.yaml
kubectl apply -f k8s/quota.yaml

# Installer le chart Minikube
helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml
kubectl rollout status deployment/kube-train

# ── Vérifications post-install ────────────────────────────────────────────────
helm list                             # toutes les releases du namespace
helm status kube-train                # état + NOTES.txt
helm get values kube-train            # valeurs actives (sans les defaults)
helm get values kube-train --all      # toutes les valeurs (avec defaults)
helm get manifest kube-train          # manifests rendus actuellement en prod
helm history kube-train               # historique des révisions

# Vérifier que le Deployment vient bien du chart (label Helm)
kubectl get deploy -l app.kubernetes.io/managed-by=Helm

# Test fonctionnel
kubectl port-forward svc/kube-train 8080:80 &
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/trains

# ── Étape 3 : Override avec values-gke.yaml ────────────────────────────────────
# Vérifier le rendu GKE sans déployer
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-gke.yaml

# Différences entre les deux overlays
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-minikube.yaml > /tmp/minikube.yaml
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-gke.yaml > /tmp/gke.yaml
diff /tmp/minikube.yaml /tmp/gke.yaml

# ── Étape 4 : CronJob ─────────────────────────────────────────────────────────
# Après ajout du template cronjob.yaml :
helm template kube-train ./kube-train-chart -s templates/cronjob.yaml \
  -f ./kube-train-chart/values-minikube.yaml

helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml

kubectl get cronjobs
kubectl get jobs --watch                    # attendre le premier job
kubectl logs job/<nom-du-job>               # logs du job

# Désactiver le CronJob via --set
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set cronjob.enabled=false

# ── Étape 5 : Upgrade atomique + rollback ─────────────────────────────────────
# Upgrade normal
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set image.tag=v6

# Simulation d'échec (image inexistante) → rollback automatique
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set image.tag=does-not-exist \
  --atomic --timeout 2m
# → devrait échouer et rollback automatiquement

helm history kube-train                     # voir la révision failed + rollback
helm rollback kube-train 1                  # rollback manuel si nécessaire

# ── Debug / troubleshooting ───────────────────────────────────────────────────
# Erreur de parsing template Go
helm lint kube-train-chart
helm template kube-train ./kube-train-chart 2>&1 | head -30

# Voir le state Helm stocké en K8s (Secrets)
kubectl get secrets -l owner=helm
kubectl get secret sh.helm.release.v1.kube-train.v1 -o yaml

# Nettoyage complet
helm uninstall kube-train
kubectl delete -f k8s/postgres-deployment.yaml
kubectl delete -f k8s/postgres-service.yaml
kubectl delete -f k8s/postgres-storage.yaml
```

---

## F4-J3: Terraform IaC

```bash
# ── Bootstrap (une seule fois) ────────────────────────────────────────────────
# Créer le bucket GCS pour le state Terraform
gcloud storage buckets create gs://kube-train-terraform-state \
  --project=kube-train-project \
  --location=europe-west1 \
  --uniform-bucket-level-access

gcloud storage buckets update gs://kube-train-terraform-state --versioning

# ── Workflow quotidien ────────────────────────────────────────────────────────
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra

terraform init                   # initialise provider + backend GCS
terraform fmt -recursive         # reformate les fichiers .tf
terraform validate               # vérifie la syntaxe sans appel réseau
terraform plan                   # diff state ↔ infra ↔ config HCL
terraform apply                  # applique le plan (demande confirmation)
terraform apply -auto-approve    # applique sans confirmation (CI/CD)
terraform destroy                # supprime toutes les ressources (fin de journée)

# ── Import de ressources existantes ──────────────────────────────────────────
# GKE cluster
terraform import \
  google_container_cluster.main \
  projects/kube-train-project/locations/europe-west1/clusters/kube-train-cluster

# Cloud SQL instance
terraform import \
  google_sql_database_instance.main \
  projects/kube-train-project/instances/kube-train-db

# Artifact Registry
terraform import \
  google_artifact_registry_repository.docker \
  projects/kube-train-project/locations/europe-west1/repositories/kube-train-repo

# Inspecter une ressource importée
terraform state show google_container_cluster.main
terraform state list             # liste toutes les ressources dans le state

# ── Vérifications post-apply ─────────────────────────────────────────────────
terraform output                 # affiche les outputs définis

gcloud container clusters list --project=kube-train-project
gcloud sql instances describe kube-train-db --project=kube-train-project
gcloud artifacts repositories list --location=europe-west1 --project=kube-train-project
gcloud pubsub topics list --project=kube-train-project
gcloud iam service-accounts list --project=kube-train-project

# ── Économies GCP ─────────────────────────────────────────────────────────────
# Stopper Cloud SQL sans détruire l'infra
gcloud sql instances patch kube-train-db \
  --activation-policy=NEVER \
  --project=kube-train-project

# Redémarrer Cloud SQL le lendemain
gcloud sql instances patch kube-train-db \
  --activation-policy=ALWAYS \
  --project=kube-train-project

# Pipeline GitHub Actions Terraform
gh run list --workflow terraform-infra
gh run watch                     # suivre le run en temps réel
```

---

## F4-J4: Istio Service Mesh (Cloud Service Mesh — GKE Fleet)

> ⚠️ kube-train utilise **Cloud Service Mesh managé** (Traffic Director), pas `istioctl install`.
> Namespace cible : `default` (pas `kube-train` comme dans le TP théorique).

```bash
# ── Vérification état du mesh (Fleet) ────────────────────────────────────────
gcloud container fleet mesh describe --project=kube-train-project
# → controlPlaneManagement.state: ACTIVE = mesh opérationnel
# → Si CONFIG_VALIDATION_ERROR → APIs manquantes (voir ci-dessous)

# APIs requises pour Cloud Service Mesh (gérer via infra/apis.tf)
gcloud services list --enabled --project=kube-train-project \
  | grep -E "trafficdirector|meshca|networksecurity|networkservices"
# Les 4 doivent être présentes

# Activer manuellement si nécessaire :
gcloud services enable \
  trafficdirector.googleapis.com \
  meshca.googleapis.com \
  networksecurity.googleapis.com \
  networkservices.googleapis.com

# ── Vérification sidecar Envoy injecté ───────────────────────────────────────
kubectl get pods
# → kube-train : 3/3 (api + cloud-sql-proxy + istio-proxy)
# → notification : 2/2 (notification-container + istio-proxy)
# → otel-collector : 2/2 (otel-collector + istio-proxy)

# Logs proxy — connexion Traffic Director (pas Istiod)
kubectl logs deployment/kube-train-deployment -c istio-proxy --tail=5
# → xdsproxy connected to upstream XDS server: meshconfig.googleapis.com:443

# Vérifier que le label namespace est correct pour ASM managé
kubectl get namespace default --show-labels | grep istio
# → meshconfig.io/proxy-version=asm-managed-rapid

# ── Annotations obligatoires (Cloud SQL Auth Proxy + Istio) ──────────────────
# Ces annotations sont dans deployment-gke.yaml et deployment-gke-v2.yaml
#
# traffic.sidecar.istio.io/excludeOutboundIPRanges: "169.254.169.254/32"
# → Exclut le GKE metadata server (tokens Workload Identity pour cloud-sql-proxy)
# → Sans cette annotation : cloud-sql-proxy ne peut pas s'authentifier → crash
#
# traffic.sidecar.istio.io/excludeInboundPorts: "5432,15020"
# → 5432 : Cloud SQL Auth Proxy gère son propre TLS — pas de mTLS Istio entrant
# → 15020 : port de santé pilot-agent — évite les probes échouent avant xDS prêt

# ── Étape 2 : mTLS STRICT ─────────────────────────────────────────────────────
kubectl apply -f k8s/notification-service.yaml          # Service ClusterIP requis
kubectl apply -f k8s/network-policy-notification.yaml   # Allow L4 (port 8081)
kubectl apply -f k8s/peer-authentication-strict.yaml    # STRICT sur notification-pod
kubectl apply -f k8s/istio-test-pods.yaml               # mesh-client + plain-client

# Attendre la propagation Traffic Director (~60s)
sleep 60

# Test mTLS
kubectl wait pod/mesh-client pod/plain-client --for=condition=Ready --timeout=60s

# Pod meshé (sidecar + SA kube-train-api-sa) → doit répondre
kubectl exec -n default mesh-client -c curl -- \
  curl -s http://notification-service:8081/actuator/health
# → {"groups":["liveness","readiness"],"status":"UP"}

# Pod sans sidecar → doit être rejeté
kubectl exec -n default plain-client -c curl -- \
  curl -sv http://notification-service:8081/actuator/health 2>&1 | grep -E "Connected|reset|200"
# → * Connected to notification-service ...
# → * Recv failure: Connection reset by peer

# ── Étape 3 : Canary 90/10 ───────────────────────────────────────────────────
# 1. Appliquer le label version: v1 sur le déploiement existant
kubectl apply -f k8s/deployment-gke.yaml
kubectl rollout status deployment/kube-train-deployment

# 2. Vérifier que le git pull a mis à jour le tag dans deployment-gke-v2.yaml
git pull && kubectl apply -f k8s/deployment-gke-v2.yaml
kubectl rollout status deployment/kube-train-deployment-v2
# Si ImagePullBackOff (CI/CD pas encore fini) :
IMAGE=$(kubectl get deployment kube-train-deployment \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="api-container")].image}')
kubectl set image deployment/kube-train-deployment-v2 api-container=$IMAGE

# 3. Appliquer DestinationRule + VirtualService
kubectl apply -f k8s/istio-canary.yaml

# 4. Vérifier les labels v1/v2 sur les pods
kubectl get pods -l app=kube-train-pod --show-labels | grep "version="

# 5. Test canary (50+ requêtes pour avoir un échantillon valide)
kubectl exec -n default mesh-client -c curl -- \
  sh -c 'for i in $(seq 1 50); do curl -s http://kube-train-service/ > /dev/null; done'

# 6. Vérifier les stats Envoy (clusters v1/v2)
kubectl exec -n default mesh-client -c istio-proxy -- \
  pilot-agent request GET clusters 2>/dev/null | grep -E "kube-train-service.*rq_total"
# → outbound|80|v1|... rq_total::~45
# → outbound|80|v2|... rq_total::~5

# 7. Logs application v2 (stdout Spring Boot — pas istio-proxy qui va vers Cloud Logging)
kubectl logs -l version=v2 -c api-container | grep "GET /"

# ── Étape 4a : AuthorizationPolicy ───────────────────────────────────────────
kubectl apply -f k8s/authorization-policy.yaml
sleep 60  # attendre propagation Traffic Director

# Test autorisé (kube-train-api-sa)
kubectl exec -n default mesh-client -c curl -- \
  curl -s http://notification-service:8081/actuator/health
# → {"status":"UP"}

# Test refusé (autre SA)
kubectl run other-client --image=curlimages/curl:8.8.0 \
  --restart=Never --command -- sh -c "sleep 600"
kubectl wait pod/other-client --for=condition=Ready --timeout=60s
kubectl exec -n default other-client -- \
  curl -sv http://notification-service:8081/actuator/health 2>&1 | grep -E "200|403|RBAC"
# → < HTTP/1.1 403 Forbidden
# → RBAC: access denied

# ── Étape 4b : Fault injection 500ms sur /reservations ───────────────────────
kubectl apply -f k8s/istio-fault-injection.yaml

# Mesure de latence injectée
time kubectl exec -n default mesh-client -c curl -- \
  curl -s http://kube-train-service/reservations/TEST > /dev/null
# → real ~0m0.980s (500ms de délai Istio + overhead)

# Désactiver la fault injection (remettre le canary 90/10 sans latence)
kubectl apply -f k8s/istio-canary.yaml

# ── Debug — Stats Envoy complètes ─────────────────────────────────────────────
# Clusters : liste tous les upstreams connus d'un pod
kubectl exec -n default mesh-client -c istio-proxy -- \
  pilot-agent request GET clusters 2>/dev/null | grep "kube-train"

# Routes : vérifier que le VirtualService est bien dans la config Envoy
kubectl exec -n default mesh-client -c istio-proxy -- \
  pilot-agent request GET routes 2>/dev/null | grep -A5 "kube-train-service"

# Config dump complet (verbose)
kubectl exec -n default mesh-client -c istio-proxy -- \
  pilot-agent request GET config_dump 2>/dev/null | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(json.dumps(d,indent=2))" 2>/dev/null \
  | grep -A5 "VirtualService" | head -30

# ── Cleanup fin de journée ────────────────────────────────────────────────────
# Supprimer les pods et déploiement de test
kubectl delete pod mesh-client plain-client other-client --ignore-not-found
kubectl delete -f k8s/deployment-gke-v2.yaml --ignore-not-found
kubectl delete -f k8s/istio-canary.yaml --ignore-not-found        # DR + VS
kubectl delete -f k8s/istio-fault-injection.yaml --ignore-not-found
kubectl delete -f k8s/authorization-policy.yaml --ignore-not-found
kubectl delete -f k8s/peer-authentication-strict.yaml --ignore-not-found
# Garder : notification-service.yaml, network-policy-notification.yaml (utiles en prod)

# Stopper Cloud SQL + terraform destroy (si fin de formation)
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
# OU :
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra && terraform destroy
```

---

## F4-J5: SRE & Observabilité Production

```bash
# ── Vérifier les métriques dans Cloud Monitoring ─────────────────────────────
# Prérequis : OTel Collector déployé avec prometheus receiver (k8s/otel-collector.yaml)
# Prérequis WI : default SA annoté + binding compute SA

TOKEN=$(gcloud auth print-access-token)

# Lister les time series disponibles (métriques Spring Boot)
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/timeSeries?filter=metric.type%3D%22workload.googleapis.com%2Fhttp_server_requests_seconds_count%22&interval.startTime=$(date -u -d '10 minutes ago' +%Y-%m-%dT%H:%M:%SZ)&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  | python3 -m json.tool | head -40

# ── Workload Identity (fix obligatoire GKE Autopilot) ────────────────────────
kubectl annotate serviceaccount default -n default \
  iam.gke.io/gcp-service-account=399291708401-compute@developer.gserviceaccount.com \
  --overwrite

gcloud iam service-accounts add-iam-policy-binding \
  399291708401-compute@developer.gserviceaccount.com \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:kube-train-project.svc.id.goog[default/default]" \
  --project=kube-train-project

kubectl rollout restart deployment/otel-collector

# ── Créer le Service Cloud Monitoring ────────────────────────────────────────
TOKEN=$(gcloud auth print-access-token)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services" \
  -d '{"displayName": "kube-train-api", "custom": {}}' \
  | python3 -m json.tool
# → noter SERVICE_ID dans la réponse "name"

# ── Créer SLO availability (request-based) ───────────────────────────────────
SERVICE_ID="WLmPk5jSRuy_WlzRaWAv4w"   # valeur TP 26/06/2026
TOKEN=$(gcloud auth print-access-token)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services/${SERVICE_ID}/serviceLevelObjectives" \
  -d '{
    "displayName": "Availability /trains - 99.9%",
    "serviceLevelIndicator": {"requestBased": {"goodTotalRatio": {
      "goodServiceFilter": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_count\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/trains\" AND metric.labels.status=\"200\"",
      "totalServiceFilter": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_count\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/trains\""
    }}},
    "goal": 0.999, "rollingPeriod": "2592000s"
  }' | python3 -m json.tool

# ── Créer SLO latence (window-based) ─────────────────────────────────────────
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services/${SERVICE_ID}/serviceLevelObjectives" \
  -d '{
    "displayName": "Latency /reservations max < 500ms - 95%",
    "serviceLevelIndicator": {"windowsBased": {"windowPeriod": "60s",
      "metricMeanInRange": {
        "timeSeries": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_max\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/reservations/{id}\"",
        "range": {"max": 0.5}
      }
    }},
    "goal": 0.95, "rollingPeriod": "2592000s"
  }' | python3 -m json.tool

# ── Créer notification channel + alerte burn rate ────────────────────────────
SLO1_ID="dw9GqvhqTym5-uvCp9vSuA"   # valeur TP 26/06/2026
CHANNEL=$(curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/notificationChannels" \
  -d '{"type": "email", "displayName": "kube-train alerts", "labels": {"email_address": "sami.yanezcarbonell@gmail.com"}}')
CHANNEL_NAME=$(echo $CHANNEL | python3 -c "import sys,json; print(json.load(sys.stdin)['name'])")

curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/alertPolicies" \
  -d "{
    \"displayName\": \"SLO Burn Rate - Availability /trains\",
    \"conditions\": [{\"displayName\": \"Fast burn rate >14.4x sur 1h\",
      \"conditionThreshold\": {
        \"filter\": \"select_slo_burn_rate(\\\"projects/399291708401/services/${SERVICE_ID}/serviceLevelObjectives/${SLO1_ID}\\\", 3600s)\",
        \"comparison\": \"COMPARISON_GT\", \"thresholdValue\": 14.4, \"duration\": \"0s\"
      }}],
    \"combiner\": \"OR\", \"enabled\": true,
    \"notificationChannels\": [\"${CHANNEL_NAME}\"]
  }" | python3 -m json.tool | head -10

# ── Créer dashboard MQL Golden Signals ───────────────────────────────────────
# Voir /tmp/dashboard.json ou k8s/ pour le JSON complet
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v1/projects/kube-train-project/dashboards" \
  --data-binary @/tmp/dashboard.json | python3 -m json.tool | head -5

# ── Gatekeeper — installer et appliquer les contraintes ──────────────────────
kubectl apply -f https://raw.githubusercontent.com/open-policy-agent/gatekeeper/v3.14.0/deploy/gatekeeper.yaml
kubectl rollout status deployment/gatekeeper-controller-manager -n gatekeeper-system
kubectl rollout status deployment/gatekeeper-audit -n gatekeeper-system

kubectl apply -f k8s/gatekeeper-ct-allowed-repos.yaml
kubectl apply -f k8s/gatekeeper-ct-required-limits.yaml
sleep 15

kubectl apply -f - << 'EOF'
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: K8sAllowedRepos
metadata:
  name: allowed-repos-kube-train
spec:
  enforcementAction: warn
  match:
    kinds:
      - apiGroups: [""]
        kinds: ["Pod"]
    namespaces: ["default"]
  parameters:
    repos:
      - "europe-west1-docker.pkg.dev/kube-train-project/"
      - "otel/opentelemetry-collector-contrib"
      - "gcr.io/"
      - "europe-west1-artifactregistry.gcr.io/"
EOF

kubectl apply -f - << 'EOF'
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: K8sRequiredLimits
metadata:
  name: required-limits-kube-train
spec:
  enforcementAction: warn
  match:
    kinds:
      - apiGroups: [""]
        kinds: ["Pod"]
    namespaces: ["default"]
EOF

kubectl get constraints

# ── Vérifier les violations (après ~60s audit) ────────────────────────────────
kubectl describe k8sallowedrepos allowed-repos-kube-train | grep -A 10 "Violations"
kubectl describe k8srequiredlimits required-limits-kube-train | grep -A 10 "Violations"

# ── Générer du trafic pour alimenter les SLOs ─────────────────────────────────
LB_IP=$(kubectl get svc kube-train-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
for i in $(seq 1 50); do curl -s http://${LB_IP}/trains > /dev/null; done
for i in $(seq 1 20); do curl -s http://${LB_IP}/reservations/TEST > /dev/null; done

# ── Cleanup fin de journée ────────────────────────────────────────────────────
# Optionnel : supprimer Gatekeeper si non nécessaire
kubectl delete -f https://raw.githubusercontent.com/open-policy-agent/gatekeeper/v3.14.0/deploy/gatekeeper.yaml

# Stopper Cloud SQL + terraform destroy
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra && terraform destroy
```
