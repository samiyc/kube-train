# Notes J2 — Helm & Packaging Kubernetes

> Théorie du matin : du manifest plat au chart réutilisable multi-environnement.

---

### 0) Pourquoi Helm ?

**Problème avec les manifests plats (`k8s/`)** :
- `deployment.yaml` et `deployment-gke.yaml` ont le même pod spec — duplication
- Changer le tag de l'image = éditer le fichier à la main (risque d'erreur, pas traçable)
- Pas de lifecycle : pas d'historique, pas de rollback, pas de `diff` avant d'appliquer
- Pas de multi-env propre : on copie/colle le fichier en changeant quelques valeurs

**Ce que Helm apporte** :
- **Templating** : les valeurs variables (`image.tag`, `replicas`) sont dans `values.yaml`
- **Release lifecycle** : `install` → `upgrade` → `rollback` → `uninstall` avec historique
- **Multi-env** : un seul chart, des fichiers `values-*.yaml` par environnement
- **Atomic deploy** : rollback automatique si le déploiement échoue

**Analogie** : Helm est à Kubernetes ce que Maven est à Java — un gestionnaire de packaging avec lifecycle.

---

### 1) Structure d'un chart

```
kube-train-chart/
  Chart.yaml              # métadonnées obligatoires (name, version, appVersion)
  values.yaml             # valeurs par défaut (toutes les variables du chart)
  values-minikube.yaml    # overlay Minikube (surcharge values.yaml)
  values-gke.yaml         # overlay GKE
  .helmignore             # fichiers exclus du packaging (comme .gitignore)
  templates/
    _helpers.tpl          # fonctions Go réutilisables (define/include)
    deployment.yaml       # template Deployment
    service.yaml          # template Service
    configmap.yaml        # template ConfigMap
    cronjob.yaml          # template CronJob
    NOTES.txt             # texte affiché après helm install (aide utilisateur)
```

#### Chart.yaml — les champs essentiels

```yaml
apiVersion: v2           # Helm 3 = v2, Helm 2 = v1
name: kube-train-chart
description: Chart Helm pour l'API kube-train
type: application        # application (déployable) vs library (réutilisable)
version: 0.1.0           # version du chart (suit le chart lui-même)
appVersion: "1.0.0"      # version de l'application packagée (informatif)
```

---

### 2) Go templating — syntaxe de base

Helm utilise le moteur de template Go (`text/template`). Les expressions sont dans `{{ }}`.

#### Accès aux valeurs

```yaml
# values.yaml
image:
  repository: europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api
  tag: "latest"
  pullPolicy: IfNotPresent

replicaCount: 2
```

```yaml
# templates/deployment.yaml
replicas: {{ .Values.replicaCount }}
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
imagePullPolicy: {{ .Values.image.pullPolicy }}
```

#### Objets built-in

| Objet | Contenu | Exemple |
|---|---|---|
| `.Values` | Contenu de `values.yaml` (+ overrides) | `{{ .Values.image.tag }}` |
| `.Release.Name` | Nom de la release Helm | `{{ .Release.Name }}` → `kube-train` |
| `.Release.Namespace` | Namespace cible | `{{ .Release.Namespace }}` |
| `.Chart.Name` | Nom du chart | `{{ .Chart.Name }}` → `kube-train-chart` |
| `.Chart.Version` | Version du chart | `{{ .Chart.Version }}` → `0.1.0` |

#### Fonctions utiles

```yaml
# quote : ajoute des guillemets (important pour les valeurs avec : ou emojis)
TRAIN_MESSAGE: {{ .Values.config.trainMessage | quote }}
# → TRAIN_MESSAGE: "🚨 GREVE : Aucun train ne circule."

# toYaml + nindent : sérialiser un bloc YAML avec indentation
resources:
  {{- toYaml .Values.resources | nindent 10 }}
# Le - dans {{- supprime le retour à la ligne AVANT l'expression

# default : valeur de fallback si la valeur est vide
image: {{ .Values.image.tag | default "latest" }}

# include : appeler un helper défini dans _helpers.tpl
name: {{ include "kube-train-chart.fullname" . }}
```

#### Conditionnels et boucles

```yaml
# if / else
{{- if .Values.cronjob.enabled }}
apiVersion: batch/v1
kind: CronJob
...
{{- end }}

# range (itération sur une liste)
env:
{{- range .Values.extraEnv }}
  - name: {{ .name }}
    value: {{ .value | quote }}
{{- end }}
```

#### Contrôle des espaces

`{{- ... -}}` : supprime les espaces/newlines avant (`-` gauche) et/ou après (`-` droit).

```yaml
# Sans contrôle : ligne vide indésirable
metadata:
  name: kube-train
  
  labels:

# Avec {{ - }} : résultat propre
metadata:
  name: {{ include "kube-train-chart.fullname" . }}
  labels:
    {{- include "kube-train-chart.labels" . | nindent 4 }}
```

---

### 3) `_helpers.tpl` — fonctions réutilisables

Le scaffold Helm génère automatiquement `templates/_helpers.tpl` avec des helpers standards.

```
{{/*
Nom du chart (tronqué à 63 caractères — limite DNS K8s)
*/}}
{{- define "kube-train-chart.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Nom complet : release-name + chart-name (ou fullnameOverride)
*/}}
{{- define "kube-train-chart.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Labels standards Helm (utilisés dans metadata.labels)
*/}}
{{- define "kube-train-chart.labels" -}}
helm.sh/chart: {{ include "kube-train-chart.chart" . }}
app.kubernetes.io/name: {{ include "kube-train-chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
```

**Usage dans un template** :
```yaml
metadata:
  name: {{ include "kube-train-chart.fullname" . }}
  labels:
    {{- include "kube-train-chart.labels" . | nindent 4 }}
```

**Pourquoi `fullnameOverride: "kube-train"` dans values.yaml** : par défaut, le nom serait `kube-train-kube-train-chart` (release + chart). L'override évite cette redondance.

---

### 4) Release lifecycle

```
helm install   →  crée les ressources (révision 1)
helm upgrade   →  met à jour (révision 2, 3…)
helm rollback  →  revient à une révision précédente
helm uninstall →  supprime toutes les ressources + l'historique
```

#### Commandes clés

```bash
# install ou upgrade (idempotent — recommandé)
helm upgrade --install kube-train ./kube-train-chart -f values-gke.yaml

# voir l'état de la release
helm list                   # toutes les releases du namespace
helm status kube-train      # état + NOTES.txt
helm get values kube-train  # valeurs utilisées pour la release actuelle
helm get manifest kube-train # manifests rendus actuellement déployés

# historique
helm history kube-train
# → REVISION  STATUS     DESCRIPTION
#   1         deployed   Install complete
#   2         deployed   Upgrade complete
#   3         failed     Upgrade "kube-train" failed: ...

# rollback à la révision 1
helm rollback kube-train 1
```

#### `--atomic` — le déploiement safe

```bash
helm upgrade --install kube-train ./kube-train-chart \
  -f values-gke.yaml \
  --atomic --timeout 5m
```

- Attend que tous les pods soient **Ready** (selon les probes)
- Si timeout ou pod qui crashe → **rollback automatique** à la révision précédente
- Le service reste disponible sur l'ancienne version pendant le rollback

**Piège** : sans `startupProbe`/`readinessProbe` fiables, `--atomic` ne détecte pas les problèmes applicatifs (le pod peut être `Running` mais l'app ne répond pas).

---

### 5) Hiérarchie des values

Priorité croissante (chaque niveau surcharge le précédent) :

```
1. values.yaml                    ← defaults du chart (base)
2. -f values-minikube.yaml        ← overlay par environnement
3. --set image.tag=abc123         ← override ponctuel (CI/CD)
4. --set-string "key=value"       ← force le type string
```

**Fusion deep-merge** : les objets imbriqués sont mergés, pas remplacés.

```yaml
# values.yaml
config:
  trainMessage: "Default"
  gcpProjectId: "kube-train-project"

# values-minikube.yaml
config:
  trainMessage: "Mode local"
  # gcpProjectId reste "kube-train-project" — non écrasé
```

**Usage CI/CD** : l'image tag est injecté par `--set image.tag=$GIT_SHA` dans le pipeline GitHub Actions sans modifier les fichiers values.

---

### 6) Jobs & CronJobs

#### Différence fondamentale

| Objet | Durée de vie | Usage |
|---|---|---|
| `Pod` | Jusqu'à restart (continu) | Service web, API |
| `Job` | Jusqu'à complétion (exit 0) | Migration, backup ponctuel |
| `CronJob` | Planifié (cron syntax) | Cleanup, rapport, outbox poller |

#### Anatomie d'un CronJob

```yaml
apiVersion: batch/v1
kind: CronJob
spec:
  schedule: "*/5 * * * *"           # cron : toutes les 5 minutes
  successfulJobsHistoryLimit: 2     # garder les 2 derniers jobs réussis
  failedJobsHistoryLimit: 1         # garder le dernier job échoué
  jobTemplate:
    spec:
      backoffLimit: 2               # nb de retries avant abandon (appartient au Job)
      activeDeadlineSeconds: 60     # durée max du Job (toutes tentatives confondues)
      template:
        spec:
          restartPolicy: OnFailure  # JAMAIS Always dans un Job
          containers:
            - name: my-task
              image: curlimages/curl:8.8.0
```

#### Règles à mémoriser pour CKAD

- `restartPolicy` dans un Job/CronJob = **`OnFailure`** ou **`Never`** (jamais `Always`)
- `backoffLimit` appartient à **`jobTemplate.spec`** (pas au pod template)
- `activeDeadlineSeconds` limite la durée totale du Job (toutes tentatives comprises)
- Un CronJob crée des Jobs → les Jobs créent des Pods

**Cycle de vie pod d'un Job** : `Pending` → `Running` → `Completed` (exit 0) ou `Failed` (exit ≠ 0, puis retry selon `backoffLimit`)

#### Syntaxe cron

```
┌────────── minute (0-59)
│ ┌──────── heure (0-23)
│ │ ┌────── jour du mois (1-31)
│ │ │ ┌──── mois (1-12)
│ │ │ │ ┌── jour de la semaine (0-7, 0=dimanche)
│ │ │ │ │
* * * * *

*/5 * * * *   → toutes les 5 minutes
0 2 * * *     → tous les jours à 2h du matin
0 0 * * 0     → tous les dimanches à minuit
```

---

### 7) Helm + ArgoCD

ArgoCD supporte nativement les charts Helm comme source d'une `Application`.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: kube-train
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/samiyc/kube-train.git
    targetRevision: main
    path: kube-train-chart           # chemin du chart dans le repo
    helm:
      valueFiles:
        - values-gke.yaml            # relatif au path ci-dessus
  destination:
    server: https://kubernetes.default.svc
    namespace: default
  syncPolicy:
    automated:
      prune: true    # supprime les ressources orphelines
      selfHeal: true # re-applique si drift détecté
```

**Différence Helm seul vs Helm + ArgoCD** :
- Helm seul : state stocké dans un Secret Kubernetes (`sh.helm.release.v1.*`)
- Helm + ArgoCD : ArgoCD observe git → compare avec l'état du cluster → déclenche `helm upgrade` si diff

**Note kube-train** : ArgoCD a été supprimé pour réduire les coûts GKE. Pour J2, on utilise `helm upgrade --atomic` (Étape 5 Option B).

---

### 8) Périmètre du chart kube-train-chart

Le chart couvre **l'API** uniquement. Ce qui reste en dehors du chart (déployé séparément) :

| Ressource | Lieu | Raison |
|---|---|---|
| `Deployment` API | ✅ Dans le chart | Coeur du packaging |
| `Service` API | ✅ Dans le chart | Lié au Deployment |
| `ConfigMap` API | ✅ Dans le chart | Config paramétrable par env |
| `CronJob` outbox | ✅ Dans le chart | Tâche liée à l'app |
| `ServiceAccount` + RBAC | ⚠️ Bonus TP | Souvent dans un chart dédié ou géré par IAM |
| PostgreSQL | ❌ Hors chart | Infra — J3 Terraform |
| Cloud SQL Proxy sidecar | ✅ Dans le chart (template) | Sidecar GKE dans le Deployment |
| `ResourceQuota` + `LimitRange` | ❌ Hors chart | Gouvernance namespace — ops |
| `namespace-pss.yaml` | ❌ Hors chart | Politique cluster — ops |
| Service notification | ❌ Chart séparé futur | Workload distinct |

---

### 9) Points clés entretien J2

| Question | Réponse courte |
|---|---|
| **Quelle est la différence entre `values.yaml` et `--set` ?** | `values.yaml` = defaults du chart ; `--set` = override ponctuel, priorité max. En CI/CD, `--set image.tag=$SHA` pour injecter le tag sans modifier les fichiers. |
| **Qu'est-ce que `helm upgrade --atomic` garantit ?** | Que le cluster revient à la version précédente si le déploiement ne converge pas (pods non Ready dans le timeout). |
| **`toYaml .Values.resources \| nindent 10` — à quoi sert nindent ?** | Indente le bloc YAML de 10 espaces pour l'aligner correctement dans le template. Sans nindent, le YAML serait mal indenté. |
| **`restartPolicy` dans un CronJob : quelles valeurs valides ?** | `OnFailure` ou `Never`. `Always` est interdit — un Job doit se terminer. |
| **Où va `backoffLimit` dans un CronJob ?** | Dans `jobTemplate.spec.backoffLimit`, pas dans `spec` du CronJob ni dans le pod template. |
| **Quelle est la portée d'un `RoleBinding` qui référence un `ClusterRole` ?** | Uniquement dans le namespace du `RoleBinding` — rappel J1. |

#### Mini-checklist CKAD J2

- [ ] `helm create`, `helm lint`, `helm template`
- [ ] Écrire une expression `{{ .Values.x }}` et la tester avec `helm template`
- [ ] Utiliser `toYaml | nindent` pour un bloc `resources:`
- [ ] Écrire un `CronJob` avec les bons `restartPolicy`, `backoffLimit`, `activeDeadlineSeconds`
- [ ] Comprendre la hiérarchie values : `values.yaml` < `-f file` < `--set`
- [ ] `helm upgrade --install --atomic` : savoir le justifier
