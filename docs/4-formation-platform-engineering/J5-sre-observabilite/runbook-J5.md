# Runbook F4-J5 — SRE & Observabilité Production

> Extrait du runbook F4. Prérequis communs & debug multi-containers : voir [`../runbook.md`](../runbook.md).

```bash
# ── Vérifier les métriques dans Cloud Monitoring ─────────────────────────────
# Prérequis : OTel Collector déployé avec prometheus receiver (k8s/observability/otel-collector.yaml)
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

kubectl apply -f k8s/security/gatekeeper-ct-allowed-repos.yaml
kubectl apply -f k8s/security/gatekeeper-ct-required-limits.yaml
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
