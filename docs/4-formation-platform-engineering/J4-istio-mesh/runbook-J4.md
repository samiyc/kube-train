# Runbook F4-J4 — Istio Service Mesh (Cloud Service Mesh — GKE Fleet)

> Extrait du runbook F4. Prérequis communs & debug multi-containers : voir [`../runbook.md`](../runbook.md).

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
kubectl apply -f k8s/workloads/notification-service.yaml          # Service ClusterIP requis
kubectl apply -f k8s/network/network-policy-notification.yaml   # Allow L4 (port 8081)
kubectl apply -f k8s/istio/peer-authentication-strict.yaml    # STRICT sur notification-pod
kubectl apply -f k8s/istio/istio-test-pods.yaml               # mesh-client + plain-client

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
kubectl apply -f k8s/workloads/deployment-gke.yaml
kubectl rollout status deployment/kube-train-deployment

# 2. Vérifier que le git pull a mis à jour le tag dans deployment-gke-v2.yaml
git pull && kubectl apply -f k8s/workloads/deployment-gke-v2.yaml
kubectl rollout status deployment/kube-train-deployment-v2
# Si ImagePullBackOff (CI/CD pas encore fini) :
IMAGE=$(kubectl get deployment kube-train-deployment \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="api-container")].image}')
kubectl set image deployment/kube-train-deployment-v2 api-container=$IMAGE

# 3. Appliquer DestinationRule + VirtualService
kubectl apply -f k8s/istio/istio-canary.yaml

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
kubectl apply -f k8s/istio/authorization-policy.yaml
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
kubectl apply -f k8s/istio/istio-fault-injection.yaml

# Mesure de latence injectée
time kubectl exec -n default mesh-client -c curl -- \
  curl -s http://kube-train-service/reservations/TEST > /dev/null
# → real ~0m0.980s (500ms de délai Istio + overhead)

# Désactiver la fault injection (remettre le canary 90/10 sans latence)
kubectl apply -f k8s/istio/istio-canary.yaml

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
kubectl delete -f k8s/workloads/deployment-gke-v2.yaml --ignore-not-found
kubectl delete -f k8s/istio/istio-canary.yaml --ignore-not-found        # DR + VS
kubectl delete -f k8s/istio/istio-fault-injection.yaml --ignore-not-found
kubectl delete -f k8s/istio/authorization-policy.yaml --ignore-not-found
kubectl delete -f k8s/istio/peer-authentication-strict.yaml --ignore-not-found
# Garder : notification-service.yaml, network-policy-notification.yaml (utiles en prod)

# Stopper Cloud SQL + terraform destroy (si fin de formation)
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
# OU :
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra && terraform destroy
```
