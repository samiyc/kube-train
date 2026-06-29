# Notes J5 — SRE en pratique : SLOs, Alertes & Gatekeeper

> Formation F4 — Platform Engineering  
> Objectif : implémenter SLOs, alertes burn rate et policy-as-code — passer de la compréhension à la pratique production.

---

## Glossaire — Acronymes J5

| Acronyme | Signification | Contexte |
|---|---|---|
| SRE | Site Reliability Engineering | Discipline Google : fiabilité comme problème d'ingénierie |
| SLO | Service Level Objective | Objectif de fiabilité mesurable (ex : disponibilité ≥ 99,9 % sur 30 jours) |
| SLI | Service Level Indicator | Métrique qui mesure la fiabilité (ex : taux de requêtes HTTP 2xx) |
| SLA | Service Level Agreement | Contrat externe avec pénalités — basé sur les SLOs |
| EB | Error Budget | Budget d'erreur = 100 % - SLO (ex: 0,1 % = 43 min/mois d'indispo autorisée) |
| MQL | Monitoring Query Language | Langage de requête Cloud Monitoring GCP pour les métriques et dashboards |
| OPA | Open Policy Agent | Moteur de politique générique (CNCF) — base de Gatekeeper K8s |
| CRD | Custom Resource Definition | Extension de l'API K8s — Gatekeeper ajoute ConstraintTemplate et Constraint |
| GKE | Google Kubernetes Engine | Service Kubernetes managé sur Google Cloud Platform |
| CI/CD | Continuous Integration / Continuous Delivery | Pipeline automatisé — les SLOs mesurent sa fiabilité en production |
| P95 | Percentile 95 | 95 % des requêtes sont traitées en moins de X ms (SLI de latence) |
| P99 | Percentile 99 | 99 % des requêtes sont traitées en moins de X ms (SLI de latence strict) |
| MTTR | Mean Time To Recovery | Temps moyen de rétablissement après un incident |
| MTBF | Mean Time Between Failures | Temps moyen entre deux incidents (inverse du taux de pannes) |
| BR | Burn Rate | Vitesse de consommation de l'error budget (1× = consommation normale) |
| CT | ConstraintTemplate | CRD Gatekeeper : définit une politique en Rego (le "moule") |
| Rego | Rego Policy Language | Langage déclaratif d'OPA pour écrire des politiques |

---

## 1. Rappel SLI / SLO / SLA — La hiérarchie

> ⚠️ **Rappel F2** : La théorie a été vue en F2-J3/J4. Ce qui est nouveau en J5 : l'implémentation programmatique via l'API Cloud Monitoring.

```
┌──────────────────────────────────────────────────────────────────┐
│  SLA (Service Level Agreement)                                   │
│  Contrat externe — signé avec le client — avec pénalités         │
│  Ex : "99,5% de disponibilité par mois, sinon remboursement"     │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  SLO (Service Level Objective)                             │  │
│  │  Cible interne — définie par l'équipe SRE                  │  │
│  │  Ex : "99,9% des GET /trains répondent 2xx sur 30 jours"   │  │
│  │                                                            │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  SLI (Service Level Indicator)                       │  │  │
│  │  │  Métrique brute mesurée en temps réel                │  │  │
│  │  │  Ex : ratio(requêtes 2xx) / ratio(total requêtes)    │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

**Règle pratique** : le SLO est toujours plus strict que le SLA, pour avoir une marge de réaction avant de violer le contrat.

### Error Budget

```
Error Budget = 100% - SLO

SLO = 99,9%  →  EB = 0,1%  →  43,2 min/mois d'erreurs autorisées
SLO = 99,5%  →  EB = 0,5%  →  3,6 heures/mois
SLO = 99,0%  →  EB = 1,0%  →  7,3 heures/mois
```

**L'error budget donne une permission** : si le budget n'est pas consommé, l'équipe peut déployer des features risquées. Si le budget est épuisé → gel des déploiements jusqu'à la prochaine fenêtre.

---

## 2. Burn Rate — Le concept clé de J5

### Pourquoi les alertes SLO classiques sont insuffisantes

Une alerte qui se déclenche "quand le SLO est violé" est **trop tardive** :

```
SLO = 99,9% sur 30 jours → EB = 43 min d'indispo autorisées

Scénario : service en panne complète (100% d'erreurs)
→ EB épuisé en 43 minutes
→ L'alerte "SLO violé" se déclenche APRÈS 43 min d'incident
→ Inutilisable en pratique
```

### La solution : alerter sur le taux de consommation (burn rate)

```
burn rate = taux d'erreur observé / taux d'erreur autorisé par le SLO

Exemple avec SLO 99,9% (error rate autorisé = 0,1%) :
  - error rate actuel = 0,1%  →  burn rate = 1×   (consommation normale)
  - error rate actuel = 1,0%  →  burn rate = 10×  (consommation 10× trop rapide)
  - error rate actuel = 1,44% →  burn rate = 14,4× (budget épuisé en 50h ≈ 2 jours)
  - error rate actuel = 100%  →  burn rate = 1000× (budget épuisé en 2,6 min)
```

### Multi-window multi-burn-rate (Google SRE Workbook)

Le pattern recommandé utilise **deux fenêtres par niveau** pour réduire les faux positifs :

| Sévérité | Fenêtre courte | Fenêtre longue | Burn rate | Budget consommé | Action |
|---|---|---|---|---|---|
| **Page (critique)** | 1h | 5min | 14,4× | 2% en 1h | Incident immédiat |
| **Page (sérieux)** | 6h | 30min | 6× | 5% en 6h | Escalade on-call |
| **Ticket** | 3 jours | 6h | 1× | 10% en 3j | Backlog SRE |

**L'alerte se déclenche si les DEUX fenêtres dépassent le seuil** : la fenêtre courte détecte vite, la fenêtre longue confirme que ce n'est pas un pic momentané.

```
Alerte "fast burn" : burn_rate_1h > 14,4 AND burn_rate_5min > 14,4
Alerte "medium burn" : burn_rate_6h > 6 AND burn_rate_30min > 6
```

---

## 3. ELI5 — Burn rate avec une analogie

**L'analogie du compte bancaire** :

```
Error budget = 43 minutes/mois = 2580 secondes de "crédit"

burn rate 1×   → tu dépenses 1€/jour, ton crédit dure 30 jours (normal)
burn rate 14,4× → tu dépenses 14,4€/jour, ton crédit dure 2 jours (critique)
burn rate 720× → tu dépenses 720€/jour, ton crédit dure 1 heure (catastrophique)
```

**Pourquoi 14,4× spécifiquement ?**

```
14,4 × 0,1% = 1,44% d'erreurs
Sur 30 jours, à 1,44% d'erreur : budget épuisé en 30/14,4 = 2,08 jours
→ Assez rapide pour pager, assez lent pour ne pas être du bruit
```

---

## 4. Cloud Monitoring SLO API

### Architecture Cloud Monitoring pour les SLOs

```
┌─────────────────────────────────────────────────────────────────┐
│  Cloud Monitoring                                               │
│                                                                 │
│  Service (kube-train-api)                                       │
│    └── SLO 1 : disponibilité GET /trains (99,9%, 30j)           │
│         ├── SLI : ratio 2xx / total (request-based)             │
│         ├── Error budget : 43 min/mois                          │
│         └── Alertes burn rate : 14,4× (1h) + 6× (6h)            │
│    └── SLO 2 : latence P95 POST /reservations (<300ms, 30j)     │
│         ├── SLI : ratio requêtes < 300ms / total                │
│         └── Alertes burn rate associées                         │
└─────────────────────────────────────────────────────────────────┘
```

### Créer un Service monitored dans Cloud Monitoring

> ⚠️ **Découverte TP** : `gcloud monitoring services create/list` et `gcloud monitoring slos create` **n'existent pas** dans le CLI gcloud (ni en alpha). Toutes les opérations SLO passent par l'**API REST** ou la Console.

```bash
TOKEN=$(gcloud auth print-access-token)

# Créer le service (conteneur pour les SLOs)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services" \
  -d '{"displayName": "kube-train-api", "custom": {}}' \
  | python3 -m json.tool
# Réponse → noter le champ "name" → "projects/NUMERO/services/SERVICE_ID"
```

### Créer un SLO de disponibilité (request-based)

```bash
SERVICE_ID="<id retourné ci-dessus>"
TOKEN=$(gcloud auth print-access-token)

curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services/${SERVICE_ID}/serviceLevelObjectives" \
  -d '{
    "displayName": "Availability /trains - 99.9%",
    "serviceLevelIndicator": {
      "requestBased": {
        "goodTotalRatio": {
          "goodServiceFilter": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_count\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/trains\" AND metric.labels.status=\"200\"",
          "totalServiceFilter": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_count\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/trains\""
        }
      }
    },
    "goal": 0.999,
    "rollingPeriod": "2592000s"
  }' | python3 -m json.tool
```

### Créer un SLO de latence (windows-based)

> ⚠️ **Découverte TP** : Spring Boot Micrometer n'exporte **pas** les buckets histogram par défaut — seuls `_count`, `_sum`, `_max` sont disponibles. Le SLO P95 réel nécessite `management.metrics.distribution.percentiles-histogram.http.server.requests=true` dans `application.properties`. En attendant, proxy via `_max` :

```bash
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/kube-train-project/services/${SERVICE_ID}/serviceLevelObjectives" \
  -d '{
    "displayName": "Latency /reservations max < 500ms - 95%",
    "serviceLevelIndicator": {
      "windowsBased": {
        "windowPeriod": "60s",
        "metricMeanInRange": {
          "timeSeries": "metric.type=\"workload.googleapis.com/http_server_requests_seconds_max\" AND resource.type=\"generic_task\" AND metric.labels.uri=\"/reservations/{id}\"",
          "range": {"max": 0.5}
        }
      }
    },
    "goal": 0.95,
    "rollingPeriod": "2592000s"
  }' | python3 -m json.tool
```

---

## 5. Alertes Burn Rate — Configuration

### Alerte fast burn (page critique)

```bash
cat > alert-fast-burn.json << 'EOF'
{
  "displayName": "kube-train - Fast Burn (14.4x)",
  "conditions": [
    {
      "displayName": "Burn rate > 14.4x sur 1h",
      "conditionThreshold": {
        "filter": "select_slo_burn_rate(\"projects/kube-train-project/services/kube-train-api/serviceLevelObjectives/availability-trains\", 3600s)",
        "comparison": "COMPARISON_GT",
        "thresholdValue": 14.4,
        "duration": "0s"
      }
    }
  ],
  "alertStrategy": {
    "notificationRateLimit": {
      "period": "3600s"
    }
  },
  "combiner": "OR",
  "notificationChannels": []
}
EOF

gcloud monitoring policies create \
  --policy-from-file=alert-fast-burn.json \
  --project=kube-train-project
```

### Notification channel (email)

```bash
gcloud monitoring channels create \
  --display-name="SRE Email" \
  --type=email \
  --channel-labels=email_address=sami.yanezcarbonell@gmail.com \
  --project=kube-train-project
```

---

## 6. Golden Signals — Dashboards MQL

Les 4 golden signals de Google SRE (Brendan Gregg USE method + Google SRE Book) :

| Signal | Question | Métrique kube-train |
|---|---|---|
| **Traffic** | Quelle est la charge actuelle ? | Requêtes/s sur `/trains`, `/reservations` |
| **Errors** | Quel est le taux d'échec ? | Ratio 5xx / total |
| **Latency** | Combien de temps les requêtes prennent-elles ? | P50, P95, P99 de `http_server_requests_seconds` |
| **Saturation** | Quelle est l'utilisation des ressources ? | CPU%, mémoire%, pool JDBC actif |

### Requêtes MQL (Monitoring Query Language)

MQL est le langage de requête de Cloud Monitoring. Syntaxe :

```
fetch <resource_type>
| metric '<metric_name>'
| filter <conditions>
| group_by [<labels>], <aggregation>
| every <interval>
```

> ⚠️ **Découverte TP** : avec l'OTel Collector (googlecloud exporter), le resource type est `generic_task` et le préfixe est `workload.googleapis.com/`. Avec GMP (PodMonitoring), ce serait `prometheus_target` et `prometheus.googleapis.com/`. Les requêtes ci-dessous reflètent l'architecture OTel réelle de kube-train.

**Traffic (requêtes/s) :**
```
fetch generic_task::workload.googleapis.com/http_server_requests_seconds_count
| align rate(1m)
| every 1m
| group_by [], [sum(val())]
```

**Error rate (taux d'erreurs 5xx) :**
```
fetch generic_task::workload.googleapis.com/http_server_requests_seconds_count
| filter (metric.labels.status =~ '5..')
| align rate(1m)
| every 1m
| group_by [], [sum(val())]
```

**Latence max (proxy — buckets non disponibles par défaut) :**
```
fetch generic_task::workload.googleapis.com/http_server_requests_seconds_max
| group_by [], [max(val())]
```

**Error budget remaining (SLO) :**
```
select_slo_budget_fraction("projects/399291708401/services/SERVICE_ID/serviceLevelObjectives/SLO_ID")
```

**Saturation CPU pod :**
```
fetch k8s_container
| metric 'kubernetes.io/container/cpu/core_usage_time'
| filter resource.labels.namespace_name == 'default'
| filter resource.labels.container_name == 'api-container'
| rate(1m)
| group_by [resource.labels.pod_name], sum
```

---

## 7. OPA / Gatekeeper — Policy as Code

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  kubectl apply -f deployment.yaml                               │
│          │                                                      │
│          ▼                                                      │
│  Kubernetes API Server                                          │
│          │                                                      │
│          ▼  webhook admission (ValidatingWebhookConfiguration)  │
│  Gatekeeper Controller (namespace: gatekeeper-system)           │
│          │                                                      │
│          ▼  évalue les Constraint actives                       │
│  OPA (Open Policy Agent) — évalue le code Rego                  │
│          │                                                      │
│          ├── ALLOW → la ressource est créée                     │
│          └── DENY  → refus avec message d'erreur                │
└─────────────────────────────────────────────────────────────────┘
```

### Les deux CRDs Gatekeeper

**`ConstraintTemplate`** — le moule (définit la règle en Rego) :
```yaml
apiVersion: templates.gatekeeper.sh/v1
kind: ConstraintTemplate
metadata:
  name: requireresourcelimits
spec:
  crd:
    spec:
      names:
        kind: RequireResourceLimits
  targets:
    - target: admission.k8s.gatekeeper.sh
      rego: |
        package requireresourcelimits

        violation[{"msg": msg}] {
          container := input.review.object.spec.containers[_]
          not container.resources.limits
          msg := sprintf("Container '%v' n'a pas de resource limits", [container.name])
        }
```

**`Constraint`** — l'instance (applique la règle sur des ressources ciblées) :
```yaml
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: RequireResourceLimits
metadata:
  name: require-limits-all-containers
spec:
  match:
    kinds:
      - apiGroups: ["apps"]
        kinds: ["Deployment"]
    namespaces: ["default"]
  enforcementAction: deny   # deny | warn | dryrun
```

### Modes d'enforcement

| Mode | Comportement | Usage |
|---|---|---|
| `deny` | Bloque la création/modification | Production — règle établie |
| `warn` | Laisse passer, ajoute un warning | Migration — transition |
| `dryrun` | Aucun effet, log uniquement | Test avant activation |

**Bonne pratique** : activer en `dryrun` d'abord pour mesurer l'impact, puis `warn`, puis `deny`.

### Contrainte 2 : images depuis Artifact Registry uniquement

```yaml
apiVersion: templates.gatekeeper.sh/v1
kind: ConstraintTemplate
metadata:
  name: allowedregistries
spec:
  crd:
    spec:
      names:
        kind: AllowedRegistries
      validation:
        openAPIV3Schema:
          properties:
            allowedPrefixes:
              type: array
              items:
                type: string
  targets:
    - target: admission.k8s.gatekeeper.sh
      rego: |
        package allowedregistries

        violation[{"msg": msg}] {
          container := input.review.object.spec.containers[_]
          prefix := input.parameters.allowedPrefixes[_]
          not startswith(container.image, prefix)
          msg := sprintf("Image '%v' non autorisée — doit commencer par un préfixe autorisé", [container.image])
        }
---
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: AllowedRegistries
metadata:
  name: only-artifact-registry
spec:
  match:
    kinds:
      - apiGroups: ["apps"]
        kinds: ["Deployment"]
    namespaces: ["default"]
  enforcementAction: deny
  parameters:
    allowedPrefixes:
      - "europe-west1-docker.pkg.dev/kube-train-project/"
      - "gcr.io/cloud-sql-connectors/"   # cloud-sql-proxy
      - "otel/opentelemetry-collector"   # otel collector
```

---

## 8. ELI5 — Rego en 3 minutes

Rego est un langage **déclaratif** : tu décris ce qui est une violation, pas comment la vérifier.

```rego
# Règle : "une violation existe SI..."
violation[{"msg": msg}] {
  container := input.review.object.spec.containers[_]   # pour chaque container
  not container.resources.limits                         # qui n'a pas de limits
  msg := sprintf("Container '%v' sans limits", [container.name])
}
```

**`input.review.object`** = le manifest Kubernetes soumis à admission.  
**`_`** = itérateur : "pour chaque élément du tableau".  
**`not`** = négation : la règle s'active quand la condition est ABSENTE.

Si le bloc `violation` ne produit aucun résultat → pas de violation → ALLOW.  
Si le bloc produit au moins un résultat → violation → DENY avec le message.

---

## 9. Cloud Load Testing (GCP-native)

Alternative à Locust pour générer de la charge depuis GCP.

```bash
# Créer un test de charge simple
gcloud alpha load-testing create-test \
  --display-name="kube-train baseline" \
  --target-url="https://api.34.78.39.236.nip.io/trains" \
  --virtual-users=10 \
  --duration=300s \
  --project=kube-train-project
```

**Usage dans ce TP** : générer ~1000 requêtes sur `/trains` et `/reservations` pour alimenter les SLOs et rendre les dashboards MQL lisibles.

> ⚠️ Facturer ~0,5€/run. Limiter à 5 min et 10-20 VU pour le TP.

---

## 10. Tests E2E avec données réelles

### Métriques observées dans Cloud Monitoring (TP J5 — 26/06/2026)

Architecture effective : Spring Boot `/actuator/prometheus` → OTel Collector (prometheus receiver) → googlecloud exporter → Cloud Monitoring.

**Métriques disponibles :**

| Métrique | Type | Labels disponibles |
|---|---|---|
| `workload.googleapis.com/http_server_requests_seconds_count` | CUMULATIVE | uri, method, status, outcome, exception, error, service_name |
| `workload.googleapis.com/http_server_requests_seconds_sum` | CUMULATIVE | idem |
| `workload.googleapis.com/http_server_requests_seconds_max` | GAUGE (1min) | idem |
| `workload.googleapis.com/spring_security_*` | GAUGE/CUMULATIVE | — |

**Resource type** : `generic_task` (task_id = `kube-train-service:80`, job = `kube-train-api`)

**Métriques absentes** : `_bucket` (histogram) — nécessite `management.metrics.distribution.percentiles-histogram.http.server.requests=true` dans `application.properties`.

### SLOs créés

| SLO | ID | Type | Goal |
|---|---|---|---|
| Availability `/trains` | `dw9GqvhqTym5-uvCp9vSuA` | request-based | 99,9% sur 30j |
| Latency `/reservations` max < 500ms | `6VZxFcVaQbmqHo59yqs3zw` | window-based (metricMeanInRange) | 95% sur 30j |

**Service Cloud Monitoring** : `WLmPk5jSRuy_WlzRaWAv4w`

### Alerte burn rate créée

- Policy ID : `18308970096500853387`
- Condition : `select_slo_burn_rate(SLO_availability, 3600s) > 14.4`
- Channel : `1718624613965527391` (email sami.yanezcarbonell@gmail.com)

### Dashboard créé

- `48aa4151-13f9-4e58-ac60-b9a68b58565e` — "kube-train — Golden Signals"
- 4 widgets : Traffic, Erreurs 5xx, Latence max, Error Budget remaining

---

## 11. Erreurs et blocages rencontrés en TP

### Blocage 1 — `gcloud monitoring` commandes inexistantes
`gcloud monitoring metrics list`, `gcloud monitoring services list`, `gcloud monitoring slos create` **n'existent pas** dans le CLI.  
**Fix** : passer exclusivement par l'API REST (`curl -H "Authorization: Bearer $TOKEN" https://monitoring.googleapis.com/v3/...`).

### Blocage 2 — Namespace GMP incorrect
NetworkPolicy créée avec `namespaceSelector: gmp-system` mais le vrai namespace est `gke-gmp-system`.  
**Fix** : `kubectl patch networkpolicy allow-gmp-scraping --type=json -p='[{"op":"replace","path":"/spec/ingress/0/from/0/namespaceSelector/matchLabels/kubernetes.io~1metadata.name","value":"gke-gmp-system"}]'`

### Blocage 3 — OTel : PermissionDenied cloudtrace.traces.patch
Le compute SA `399291708401-compute@developer.gserviceaccount.com` manquait `roles/cloudtrace.agent` malgré `roles/editor`.  
**Fix** : `gcloud projects add-iam-policy-binding kube-train-project --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" --role="roles/cloudtrace.agent"`

### Blocage 4 — OTel : PermissionDenied monitoring.metricDescriptors.create
Même SA manquait `roles/monitoring.metricWriter`.  
**Fix** : `gcloud projects add-iam-policy-binding ... --role="roles/monitoring.metricWriter"`

### Blocage 5 — Workload Identity obligatoire sur GKE Autopilot
Même avec `roles/editor` sur le compute SA, les métriques restaient bloquées. Sur GKE Autopilot, le Workload Identity est **obligatoire** — les pods sans annotation WI sur leur K8s SA n'obtiennent aucun credential GCP.  
**Symptôme** : `PERMISSION_DENIED` (pas `UNAUTHENTICATED`) — le pod touche le metadata server mais sans binding WI il reçoit un credential vide ou rejeté.  
**Fix** :
```bash
kubectl annotate serviceaccount default -n default \
  iam.gke.io/gcp-service-account=399291708401-compute@developer.gserviceaccount.com --overwrite

gcloud iam service-accounts add-iam-policy-binding \
  399291708401-compute@developer.gserviceaccount.com \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:kube-train-project.svc.id.goog[default/default]"
```

### Blocage 6 — LoadBalancer IP changée après redéploiement
L'IP `34.78.39.236` (nginx-ingress F3) avait changé vers `34.76.253.8`.  
**Fix** : `kubectl get svc kube-train-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}'`

### Blocage 7 — `windowBased` vs `windowsBased`
L'API SLO Cloud Monitoring utilise `windowsBased` (avec un **s**), pas `windowBased`.

---

## 12. Fichiers créés dans kube-train (récapitulatif)

| Fichier | Rôle | Statut |
|---|---|---|
| `k8s/pod-monitoring.yaml` | PodMonitoring GMP (scraping via Google Managed Prometheus) | Créé — GMP ne scrape pas kube-train en pratique, pivotage vers OTel |
| `k8s/network-policy-gmp.yaml` | Autorise port 8080 ingress depuis `gke-gmp-system` | Créé + patché (namespace corrigé) |
| `k8s/network-policy-otel-scraping.yaml` | Autorise port 8080 ingress depuis le pod `otel-collector` | Créé |
| `k8s/otel-collector.yaml` | Modifié : ajout prometheus receiver + pipeline metrics | Modifié |
| `k8s/gatekeeper-ct-allowed-repos.yaml` | ConstraintTemplate : images depuis registres autorisés seulement | Créé |
| `k8s/gatekeeper-ct-required-limits.yaml` | ConstraintTemplate : resource limits obligatoires sur containers | Créé |

**Ressources GCP créées (non versionées) :**
- Service Cloud Monitoring `kube-train-api` → `WLmPk5jSRuy_WlzRaWAv4w`
- SLO availability + SLO latency
- Alert policy burn rate 14,4×
- Notification channel email
- Dashboard "Golden Signals"
- Gatekeeper Constraints `allowed-repos-kube-train` + `required-limits-kube-train` (en `warn`)

---

## 13. Pour aller plus loin

### 13.1 SLO-based alerting vs threshold alerting

| Critère | Threshold alert | Burn rate alert |
|---|---|---|
| Ce qu'elle mesure | Une valeur brute (ex: error rate > 5%) | La vitesse de consommation du budget SLO |
| Faux positifs | Nombreux (pic transitoire = alert) | Réduits (fenêtre courte + longue) |
| Contexte business | Aucun | Oui — liée à un objectif de fiabilité |
| Détection précoce | Non | Oui — avant violation du SLO |
| Fatigue d'alerte | Élevée | Faible si bien calibré |

### 13.2 Pourquoi Gatekeeper plutôt que PSS/securityContext ?

| Critère | PSS / securityContext | Gatekeeper / OPA |
|---|---|---|
| Périmètre | Sécurité pod (capabilities, root, FS) | N'importe quelle règle métier |
| Extensibilité | Limité aux champs K8s natifs | Illimité — du code Rego |
| Erreur message | Générique K8s | Personnalisable par contrainte |
| Audit mode | Non | `dryrun` + `warn` avant `deny` |
| Cas d'usage | Hardening sécurité baseline | Policy engineering (registries, labels, naming, quotas custom) |

Les deux sont complémentaires : PSS protège la sécurité bas niveau, Gatekeeper enforce les politiques organisationnelles.

### 13.3 Error budget policy — Quand geler les déploiements ?

Une error budget policy formalise les règles d'utilisation du budget :

```
Si error budget restant > 50% → déploiements et expériences autorisés
Si error budget restant 10-50% → déploiements autorisés, aucune expérience
Si error budget restant < 10% → gel des déploiements, focus fiabilité uniquement
Si SLO violé (budget 0%) → post-mortem obligatoire avant reprise
```

Cette politique transforme le SLO en outil de décision opérationnelle, pas juste en dashboard.
