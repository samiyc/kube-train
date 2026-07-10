# `gcloud monitoring` — pourquoi certaines commandes n'existent pas ?

> Contexte : F4-J5 SRE / Cloud Monitoring. Vérifié le **2026-07-09** sur la documentation officielle Google Cloud.

---

## Réponse courte

**Ce n'est pas une dépréciation récente.**  
Les commandes suivantes ne font tout simplement **pas partie** de la surface `gcloud monitoring` publiée :

- `gcloud monitoring services list|create`
- `gcloud monitoring slos list|create`
- `gcloud monitoring metrics list`

Pour les **Services Monitoring** et **SLOs**, Google expose la gestion via :

1. la **Console Cloud Monitoring** ;
2. l'**API REST Cloud Monitoring v3** (`services`, `services.serviceLevelObjectives`).

Le CLI `gcloud monitoring` couvre surtout les dashboards, alert policies, snoozes, uptime checks et quelques ressources associées. Il ne remplace pas toute l'API Monitoring v3.

---

## Tableau de vérité CLI

| Commande cherchée | GA `gcloud monitoring` | `beta` | `alpha` | Verdict |
|---|---:|---:|---:|---|
| `gcloud monitoring services list` | ❌ | ❌ | ❌ | Utiliser REST : `GET /v3/projects/{PROJECT}/services` |
| `gcloud monitoring services create` | ❌ | ❌ | ❌ | Utiliser REST : `POST /v3/projects/{PROJECT}/services` |
| `gcloud monitoring slos list` | ❌ | ❌ | ❌ | Utiliser REST : `GET /v3/projects/{PROJECT}/services/{SERVICE}/serviceLevelObjectives` |
| `gcloud monitoring slos create` | ❌ | ❌ | ❌ | Utiliser REST : `POST /v3/projects/{PROJECT}/services/{SERVICE}/serviceLevelObjectives` |
| `gcloud monitoring metrics list` | ❌ | ❌ | ❌ | Utiliser REST `metricDescriptors.list` ou `timeSeries.list/query` |
| `gcloud monitoring dashboards ...` | ✅ | ✅ | ✅ | OK |
| `gcloud monitoring policies ...` | ✅ | ✅ | ✅ | OK |
| `gcloud monitoring snoozes ...` | ✅ | ✅ | ✅ | OK |
| `gcloud monitoring uptime ...` | ✅ | ✅ | ✅ | OK |
| `gcloud monitoring channels ...` | ❌ | ✅ | ✅ | Notifications channels en beta/alpha |
| `gcloud monitoring channel-descriptors ...` | ❌ | ✅ | ✅ | Descripteurs de channels en beta/alpha |
| `gcloud monitoring alerts ...` | ❌ | ✅ | ✅ | Alertes en beta/alpha |
| `gcloud monitoring metrics-scopes ...` | ❌ | ✅ | ✅ | Metrics scopes en beta/alpha |
| `gcloud monitoring snapshots ...` | ❌ | ❌ | ❌ | Pas dans les pages `monitoring` vérifiées |

Nuance importante : les pages officielles `alpha` et `beta` ne contiennent pas davantage `services`, `slos` ou `metrics list`. Le constat du TP était donc correct.

---

## Ce que `gcloud monitoring` sait faire

Exemples utiles :

```bash
# Dashboards
gcloud monitoring dashboards list
gcloud monitoring dashboards describe DASHBOARD_ID
gcloud monitoring dashboards create --config-from-file=dashboard.json

# Alert policies
gcloud monitoring policies list
gcloud monitoring policies describe POLICY_ID
gcloud monitoring policies create --policy-from-file=policy.json

# Snoozes
gcloud monitoring snoozes list

# Uptime checks
gcloud monitoring uptime list-configs
gcloud monitoring uptime list-ips

# Notification channels : beta/alpha, pas GA dans la doc vérifiée
gcloud beta monitoring channels list
gcloud beta monitoring channel-descriptors list

# Metrics scopes : beta/alpha
gcloud beta monitoring metrics-scopes list
```

---

## Contournement REST pour les Services et SLOs

> À lancer depuis WSL/bash pour garder la syntaxe des exemples.

```bash
PROJECT_ID="kube-train-project"
TOKEN="$(gcloud auth print-access-token)"
```

### Lister les services Monitoring

```bash
curl -s --http1.1 \
  -H "Authorization: Bearer ${TOKEN}" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/services" \
  | python3 -m json.tool
```

### Créer un service custom

```bash
SERVICE_ID="kube-train-api"

curl -s --http1.1 -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/services?serviceId=${SERVICE_ID}" \
  -d '{
    "displayName": "kube-train-api",
    "custom": {}
  }' \
  | python3 -m json.tool
```

### Lister les SLOs d'un service

```bash
curl -s --http1.1 \
  -H "Authorization: Bearer ${TOKEN}" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/services/${SERVICE_ID}/serviceLevelObjectives" \
  | python3 -m json.tool
```

### Créer un SLO

Le JSON exact dépend du SLI choisi. Le plus simple en formation : créer une première version via la Console, copier le JSON de prévisualisation, puis l'automatiser.

```bash
SLO_ID="availability-99-9"

curl -s --http1.1 -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/services/${SERVICE_ID}/serviceLevelObjectives?serviceLevelObjectiveId=${SLO_ID}" \
  -d @slo.json \
  | python3 -m json.tool
```

---

## Alternative REST pour les métriques

Il n'y a pas de `gcloud monitoring metrics list`. Deux cas à distinguer :

### 1. Lister les descripteurs de métriques

```bash
curl -s --http1.1 -G \
  -H "Authorization: Bearer ${TOKEN}" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/metricDescriptors" \
  --data-urlencode 'filter=metric.type = starts_with("prometheus.googleapis.com/")' \
  | python3 -m json.tool
```

### 2. Lire des séries temporelles

```bash
END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
START="$(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)"

curl -s --http1.1 -G \
  -H "Authorization: Bearer ${TOKEN}" \
  "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/timeSeries" \
  --data-urlencode 'filter=metric.type="prometheus.googleapis.com/http_server_requests_seconds_count/counter"' \
  --data-urlencode "interval.startTime=${START}" \
  --data-urlencode "interval.endTime=${END}" \
  | python3 -m json.tool
```

Pour les requêtes plus analytiques, utiliser PromQL / l'API `timeSeries` / l'API `timeSeries:query`. Attention : la page officielle `timeSeries.query` signale une notice de dépréciation MQL ; éviter de construire une nouvelle stratégie long terme uniquement sur MQL.

---

## Chemin Console pour les SLOs

Dans la Console Google Cloud :

```text
Monitoring → SLOs
https://console.cloud.google.com/monitoring/services
```

Pour un service existant : ouvrir le service puis cliquer **Create SLO**.  
Pour un nouveau service : **Define service**, puis **Create SLO**.

---

## Conclusion pédagogique

Le piège vient du nom `gcloud monitoring` : on s'attend à y trouver toute la surface Cloud Monitoring. En réalité, c'est une surface CLI **partielle**.  

Pour kube-train F4-J5 :

- `gcloud monitoring policies ...` : OK pour les alert policies ;
- `gcloud monitoring dashboards ...` : OK pour les dashboards ;
- Services/SLOs : **REST API ou Console** ;
- métriques : **REST API / PromQL / requêtes Monitoring**, pas `gcloud monitoring metrics list`.

Ce n'était donc pas un bug de version locale du SDK, ni une commande dépréciée : c'est une limite de couverture du CLI.

---

## Sources officielles vérifiées

- Surface GA `gcloud monitoring` : https://docs.cloud.google.com/sdk/gcloud/reference/monitoring
- Surface beta `gcloud beta monitoring` : https://docs.cloud.google.com/sdk/gcloud/reference/beta/monitoring
- Surface alpha `gcloud alpha monitoring` : https://docs.cloud.google.com/sdk/gcloud/reference/alpha/monitoring
- Guide officiel SLO API : https://docs.cloud.google.com/stackdriver/docs/solutions/slo-monitoring/api/using-api
- Guide officiel Console SLO : https://docs.cloud.google.com/stackdriver/docs/solutions/slo-monitoring/ui/create-slo
- REST `services.list` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/services/list
- REST `services.create` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/services/create
- REST `services.serviceLevelObjectives.list` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/services.serviceLevelObjectives/list
- REST `services.serviceLevelObjectives.create` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/services.serviceLevelObjectives/create
- REST `timeSeries.list` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.timeSeries/list
- REST `metricDescriptors.list` : https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.metricDescriptors/list
- REST discovery Cloud Monitoring v3 : https://monitoring.googleapis.com/$discovery/rest?version=v3
