# TP J5 — SRE, SLOs, burn rate et policy enforcement

**Durée estimée : 3h-3h30**
**Prérequis** : Cloud Monitoring activé, métriques applicatives kube-train visibles, `gcloud` configuré sur `kube-train-project`, cluster GKE disponible

> La théorie SLI/SLO a déjà été vue en formation 2. Ici, on se concentre sur l'implémentation concrète : API/CLI Cloud Monitoring, burn rate alerting, dashboarding et policy enforcement via Gatekeeper.

---

## Étape 1 — Définir 2 SLIs concrets pour kube-train

### Objectif
Formaliser deux indicateurs SRE réellement pilotables pour kube-train :
1. **Disponibilité** de `GET /trains` via le ratio de réponses 2xx ;
2. **Latence** de `POST /reservations`, interprétée en pratique comme une exigence « P95 < 300 ms ».

### Contexte
Le projet expose déjà les métriques Spring Boot / Micrometer sur `/actuator/prometheus`. En GCP, l'approche la plus pratique pour ce TP consiste à s'appuyer sur les métriques Prometheus/Cloud Monitoring issues de `http_server_requests_seconds_*`.

### SLIs à retenir

#### SLI 1 — Disponibilité `GET /trains`
- **Type** : request-based
- **Signal** : ratio `good / total`
- **Good** : code HTTP `2xx` sur `GET /trains`
- **Total** : toutes les requêtes `GET /trains`

Exemple de filtres :

```text
good:
metric.type="prometheus.googleapis.com/http_server_requests_seconds_count/counter"
resource.type="prometheus_target"
metric.labels.method="GET"
metric.labels.uri="/trains"
metric.labels.status=monitoring.regex.full_match("2..")

total:
metric.type="prometheus.googleapis.com/http_server_requests_seconds_count/counter"
resource.type="prometheus_target"
metric.labels.method="GET"
metric.labels.uri="/trains"
```

#### SLI 2 — Latence `POST /reservations`
- **Type métier** : P95 < 300 ms
- **Implémentation pratique** : distribution/histogramme sur la métrique de latence, ou fenêtre good/bad si vous voulez réellement raisonner en percentile temporel.

Exemple de filtre sur la distribution :

```text
metric.type="prometheus.googleapis.com/http_server_requests_seconds_bucket/histogram"
resource.type="prometheus_target"
metric.labels.method="POST"
metric.labels.uri="/reservations"
```

> Pour un TP, on accepte souvent l'approximation opérationnelle suivante : « 99% des requêtes `POST /reservations` doivent finir sous 300 ms » via un `distribution-cut`. Si vous voulez être plus strict sur le P95, encodez le SLO en **window-based** (fenêtres de 5 min good/bad selon le P95 observé).

### Commandes d'exploration

```bash
gcloud monitoring metrics list \
  --filter='metric.type = starts_with("prometheus.googleapis.com/http_server_requests_seconds")' \
  --project=kube-train-project

gcloud monitoring services list --project=kube-train-project
```

### Ce que vous devez vérifier
- les métriques HTTP Micrometer de kube-train sont bien visibles dans Cloud Monitoring ;
- vous êtes capable d'isoler `GET /trains` et `POST /reservations` avec des labels fiables (`method`, `uri`, `status`) ;
- vous avez choisi explicitement une stratégie d'implémentation pour la latence : distribution-cut ou fenêtres good/bad.

### Pièges fréquents
- mélanger les requêtes de tous les endpoints et appeler cela un SLI « applicatif » ;
- bâtir un SLO de latence sans histogramme exploitable ;
- confondre le P95 métier et la façon concrète dont l'outil calcule l'objectif.

---

## Étape 2 — Créer les SLOs dans Cloud Monitoring via `gcloud`

### Objectif
Créer deux SLOs à **fenêtre glissante de 30 jours** avec une **cible de 99%**.

### Contexte
Cloud Monitoring attache les SLOs à un **service**. Selon votre setup, ce service peut provenir d'Istio, d'un service custom ou d'une découverte déjà existante. Commencez donc par récupérer l'identifiant du service que vous allez piloter.

### Récupérer l'ID du service

```bash
gcloud monitoring services list \
  --project=kube-train-project \
  --format='table(name,displayName)'

# Exemple : récupérer le nom complet si le displayName contient kube-train
export SERVICE_NAME=$(gcloud monitoring services list \
  --project=kube-train-project \
  --filter='displayName:kube-train' \
  --format='value(name)' | head -n 1)

echo $SERVICE_NAME
```

### SLO 1 — Disponibilité `GET /trains`

```bash
gcloud monitoring slos create \
  --project=kube-train-project \
  --service="$SERVICE_NAME" \
  --display-name='kube-train GET /trains availability 99% rolling 30d' \
  --goal=0.99 \
  --rolling-period=30d \
  --request-based-sli \
  --good-service-filter='metric.type="prometheus.googleapis.com/http_server_requests_seconds_count/counter" AND resource.type="prometheus_target" AND metric.labels.method="GET" AND metric.labels.uri="/trains" AND metric.labels.status=monitoring.regex.full_match("2..")' \
  --total-service-filter='metric.type="prometheus.googleapis.com/http_server_requests_seconds_count/counter" AND resource.type="prometheus_target" AND metric.labels.method="GET" AND metric.labels.uri="/trains"'
```

### SLO 2 — Latence `POST /reservations`

**Option A — pratique et simple pour le TP : distribution-cut**

```bash
gcloud monitoring slos create \
  --project=kube-train-project \
  --service="$SERVICE_NAME" \
  --display-name='kube-train POST /reservations latency 99% < 300ms rolling 30d' \
  --goal=0.99 \
  --rolling-period=30d \
  --sli-method=distribution-cut \
  --distribution-filter='metric.type="prometheus.googleapis.com/http_server_requests_seconds_bucket/histogram" AND resource.type="prometheus_target" AND metric.labels.method="POST" AND metric.labels.uri="/reservations"' \
  --range-min=0 \
  --range-max=0.3
```

**Option B — plus fidèle à l'intention P95 : SLO window-based**
- fenêtre de 5 minutes ;
- une fenêtre est `good` si le P95 de `POST /reservations` reste < 300 ms ;
- objectif : 99% de fenêtres good sur 30 jours.

> Selon la version de `gcloud`, l'encodage exact du window-based peut être plus simple via la console ou l'API Monitoring. Pour le TP, retenez surtout **la logique** : request-based pour la disponibilité, window-based pour un P95 métier strict.

### Vérification

```bash
gcloud monitoring slos list \
  --project=kube-train-project \
  --service="$SERVICE_NAME"
```

### Ce que vous devez vérifier
- les deux SLOs apparaissent bien sous le service choisi ;
- `rollingPeriod` est utilisé et non `calendarPeriod` ;
- les filtres ciblent bien les endpoints kube-train voulus ;
- l'objectif est de 99% sur 30 jours pour les deux SLOs.

### Pièges fréquents
- créer le SLO sur le mauvais service Monitoring ;
- utiliser `calendarPeriod` alors que l'équipe veut une fenêtre glissante ;
- oublier que l'ID de service/slo est différent du `displayName`.

---

## Étape 3 — Configurer une alerte burn rate (14.4× sur 1 heure)

### Objectif
Créer un channel email puis une alert policy qui déclenche lorsque l'error budget est consommé **14.4 fois trop vite sur 1 heure**.

### Canal de notification email

```bash
gcloud monitoring channels create \
  --project=kube-train-project \
  --type=email \
  --display-name='kube-train-sre-email' \
  --channel-labels=email_address='votre-adresse@example.com'

gcloud monitoring channels list \
  --project=kube-train-project \
  --format='table(name,displayName,type)'
```

Récupérez ensuite l'identifiant du channel :

```bash
export CHANNEL_ID=$(gcloud monitoring channels list \
  --project=kube-train-project \
  --filter='displayName="kube-train-sre-email"' \
  --format='value(name)' | head -n 1)
```

### Politique d'alerte burn rate
Récupérez ensuite le nom complet du SLO à surveiller :

```bash
export SLO_NAME=$(gcloud monitoring slos list \
  --project=kube-train-project \
  --service="$SERVICE_NAME" \
  --filter='displayName="kube-train GET /trains availability 99% rolling 30d"' \
  --format='value(name)' | head -n 1)
```

Créez un fichier `monitoring/burn-rate-fast.yaml` :

```yaml
displayName: "kube-train — burn rate fast 14.4x / 1h"
combiner: "OR"
conditions:
  - displayName: "GET /trains error budget burn > 14.4 sur 1h"
    conditionMonitoringQueryLanguage:
      duration: "0s"
      query: |
        fetch cloudmonitoring.googleapis.com/slo
        | filter resource.slo_name == "SLO_NAME"
        | select_slo_burn_rate(window=60m)
        | condition val() > 14.4 '1'
notificationChannels:
  - "CHANNEL_ID"
enabled: true
alertStrategy:
  autoClose: "1800s"
```

Puis appliquez-la :

```bash
gcloud monitoring policies create \
  --project=kube-train-project \
  --policy-from-file=monitoring/burn-rate-fast.yaml
```

> Remplacez `SLO_NAME` et `CHANNEL_ID` avant la création. Pour un setup plus mature, ajoutez aussi une alerte **slow burn** (par exemple `3x` sur 6h ou 1j) afin de capter les dérives lentes.

### Ce que vous devez vérifier
- le channel email existe et attend la confirmation éventuelle de l'adresse ;
- la policy cible bien un SLO précis, pas juste une métrique brute ;
- le seuil `14.4x` et la fenêtre `1h` sont bien visibles dans la règle ;
- l'alerte est reliée au concept d'error budget, pas à un simple seuil statique arbitraire.

### Pièges fréquents
- alerter sur le taux d'erreur brut sans lien avec l'error budget ;
- oublier de remplacer `SLO_ID` dans la requête MQL ;
- créer uniquement un fast burn sans alerte slow burn complémentaire.

---

## Étape 4 — Construire un dashboard Cloud Monitoring orienté SRE

### Objectif
Créer un dashboard `kube-train SRE` avec 4 panneaux :
1. traffic ;
2. error rate ;
3. latence P50 / P95 / P99 ;
4. error budget remaining.

### Requêtes recommandées

#### 1) Traffic
```mql
fetch prometheus_target
| metric 'prometheus.googleapis.com/http_server_requests_seconds_count/counter'
| filter metric.uri == '/trains'
| align rate(1m)
| every 1m
| group_by [], sum(val())
```

#### 2) Error rate
```mql
fetch prometheus_target
| metric 'prometheus.googleapis.com/http_server_requests_seconds_count/counter'
| filter metric.uri == '/trains'
| group_by [metric.status], sum(val())
```

#### 3) Latence P50 / P95 / P99
```mql
fetch prometheus_target
| metric 'prometheus.googleapis.com/http_server_requests_seconds_bucket/histogram'
| filter metric.uri == '/reservations'
| every 1m
```

#### 4) Error budget remaining
- soit via une widget SLO native de Cloud Monitoring ;
- soit via une métrique/visualisation centrée sur le SLO créé à l'étape 2.

### Création via fichier JSON
Créez un fichier `monitoring/dashboard-kube-train.json` (extrait minimal) :

```json
{
  "displayName": "kube-train SRE",
  "mosaicLayout": {
    "columns": 12,
    "tiles": [
      {
        "xPos": 0,
        "yPos": 0,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Traffic — GET /trains",
          "xyChart": {
            "dataSets": [
              {
                "plotType": "LINE",
                "timeSeriesQuery": {
                  "timeSeriesQueryLanguage": "fetch prometheus_target | metric 'prometheus.googleapis.com/http_server_requests_seconds_count/counter' | filter metric.uri == '/trains' | align rate(1m) | every 1m | group_by [], sum(val())"
                }
              }
            ]
          }
        }
      },
      {
        "xPos": 6,
        "yPos": 0,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Error rate — GET /trains",
          "xyChart": {
            "dataSets": [
              {
                "plotType": "LINE",
                "timeSeriesQuery": {
                  "timeSeriesQueryLanguage": "fetch prometheus_target | metric 'prometheus.googleapis.com/http_server_requests_seconds_count/counter' | filter metric.uri == '/trains' | group_by [metric.status], sum(val())"
                }
              }
            ]
          }
        }
      },
      {
        "xPos": 0,
        "yPos": 4,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Latency — POST /reservations",
          "xyChart": {
            "dataSets": [
              {
                "plotType": "LINE",
                "timeSeriesQuery": {
                  "timeSeriesQueryLanguage": "fetch prometheus_target | metric 'prometheus.googleapis.com/http_server_requests_seconds_bucket/histogram' | filter metric.uri == '/reservations' | every 1m"
                }
              }
            ]
          }
        }
      },
      {
        "xPos": 6,
        "yPos": 4,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Error budget remaining",
          "scorecard": {
            "timeSeriesQuery": {
              "timeSeriesQueryLanguage": "fetch cloudmonitoring.googleapis.com/slo | filter resource.slo_name == 'SLO_NAME'"
            }
          }
        }
      }
    ]
  }
}
```

### Commandes

```bash
gcloud monitoring dashboards create \
  --project=kube-train-project \
  --config-from-file=monitoring/dashboard-kube-train.json

gcloud monitoring dashboards list \
  --project=kube-train-project \
  --filter='displayName="kube-train SRE"'
```

### Ce que vous devez vérifier
- les 4 panneaux sont présents ;
- le dashboard distingue trafic, erreurs et latence au lieu de tout mélanger ;
- au moins un panneau est relié à la notion d'error budget/SLO ;
- les requêtes affichent bien des données récentes sur kube-train.

### Pièges fréquents
- construire un dashboard purement infra (CPU/RAM) sans vue user-facing ;
- oublier les percentiles P50/P95/P99 et ne garder qu'une moyenne ;
- afficher l'error budget dans un coin sans lien avec les alertes.

---

## Étape 5 — Installer Gatekeeper et créer 2 `ConstraintTemplate`

### Objectif
Mettre en place un garde-fou policy-as-code pour empêcher deux classes d'erreurs courantes :
1. image non issue d'Artifact Registry kube-train ;
2. conteneurs sans `resources.limits`.

### Installation de Gatekeeper

```bash
helm repo add gatekeeper https://open-policy-agent.github.io/gatekeeper/charts
helm repo update
helm install gatekeeper gatekeeper/gatekeeper \
  --namespace gatekeeper-system \
  --create-namespace

kubectl get pods -n gatekeeper-system
```

### ConstraintTemplate 1 — Registres autorisés uniquement

```yaml
apiVersion: templates.gatekeeper.sh/v1beta1
kind: ConstraintTemplate
metadata:
  name: k8sallowedimagerepos
spec:
  crd:
    spec:
      names:
        kind: K8sAllowedImageRepos
      validation:
        openAPIV3Schema:
          type: object
          properties:
            repos:
              type: array
              items:
                type: string
  targets:
    - target: admission.k8s.gatekeeper.sh
      rego: |
        package k8sallowedimagerepos

        violation[{"msg": msg}] {
          container := input.review.object.spec.containers[_]
          not image_allowed(container.image)
          msg := sprintf("image non autorisée: %v", [container.image])
        }

        violation[{"msg": msg}] {
          container := input.review.object.spec.initContainers[_]
          not image_allowed(container.image)
          msg := sprintf("image initContainer non autorisée: %v", [container.image])
        }

        image_allowed(image) {
          repo := input.parameters.repos[_]
          startswith(image, repo)
        }
```

```yaml
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: K8sAllowedImageRepos
metadata:
  name: kube-train-allowed-registries
spec:
  match:
    namespaces:
      - kube-train
    kinds:
      - apiGroups: [""]
        kinds: ["Pod"]
  parameters:
    repos:
      - "europe-west1-docker.pkg.dev/kube-train-project/"
```

### ConstraintTemplate 2 — Limits obligatoires

```yaml
apiVersion: templates.gatekeeper.sh/v1beta1
kind: ConstraintTemplate
metadata:
  name: k8srequiredlimits
spec:
  crd:
    spec:
      names:
        kind: K8sRequiredLimits
  targets:
    - target: admission.k8s.gatekeeper.sh
      rego: |
        package k8srequiredlimits

        violation[{"msg": msg}] {
          container := input.review.object.spec.containers[_]
          not container.resources.limits.cpu
          msg := sprintf("cpu limit manquante pour le conteneur %v", [container.name])
        }

        violation[{"msg": msg}] {
          container := input.review.object.spec.containers[_]
          not container.resources.limits.memory
          msg := sprintf("memory limit manquante pour le conteneur %v", [container.name])
        }
```

```yaml
apiVersion: constraints.gatekeeper.sh/v1beta1
kind: K8sRequiredLimits
metadata:
  name: kube-train-required-limits
spec:
  match:
    namespaces:
      - kube-train
    kinds:
      - apiGroups: [""]
        kinds: ["Pod"]
```

### Commandes

```bash
kubectl apply -f gatekeeper-allowed-registries-template.yaml
kubectl apply -f gatekeeper-allowed-registries-constraint.yaml
kubectl apply -f gatekeeper-required-limits-template.yaml
kubectl apply -f gatekeeper-required-limits-constraint.yaml
```

### Vérification avec un mauvais pod

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: bad-pod
  namespace: kube-train
spec:
  containers:
    - name: nginx
      image: nginx:latest
```

```bash
kubectl apply -f bad-pod.yaml
```

### Ce que vous devez vérifier
- Gatekeeper tourne dans `gatekeeper-system` ;
- le pod `bad-pod` est refusé car l'image n'est pas issue d'Artifact Registry kube-train ;
- même avec une image autorisée, l'absence de `resources.limits` déclenche encore un refus ;
- la policy agit comme un filet de sécurité transverse, indépendant de l'application Java.

### Pièges fréquents
- écrire une policy trop large qui casse aussi `kube-system` ou `istio-system` ;
- ne matcher que `containers` et oublier `initContainers` ;
- croire que Gatekeeper remplace les revues de code : il complète, il ne dispense pas de réfléchir.

---

## Résultat attendu en fin de TP

À la fin de J5, vous devez avoir :
- 2 SLIs explicitement définis pour kube-train ;
- 2 SLOs créés dans Cloud Monitoring avec fenêtre glissante 30 jours ;
- une alerte burn rate reliée à un vrai error budget ;
- un dashboard SRE lisible par une équipe d'astreinte ;
- Gatekeeper installé avec deux contraintes utiles et actionnables.

> Bonus senior : ajoutez ensuite un slow-burn `3x`, automatisez l'export des dashboards en JSON versionné, et faites bloquer vos PR Helm/Terraform tant que les policies Gatekeeper ne passent pas en pré-production.
