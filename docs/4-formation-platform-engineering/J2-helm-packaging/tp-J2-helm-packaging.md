# TP J2 — Packager kube-train en chart Helm

**Durée estimée : 2-3h**
**Prérequis** : Helm 3.14+ installé, cluster Minikube ou GKE, kube-train manifests dans k8s/

---

## Étape 1 — Scaffolding et premier template

### Objectif
Créer un chart Helm à partir du scaffold standard, le nettoyer, puis migrer le `Deployment` de l’API depuis `k8s/workloads/deployment-gke.yaml` vers `templates/deployment.yaml`.

> Fichiers sources à relire avant de commencer :
> - `k8s/workloads/deployment-gke.yaml`
> - `k8s/workloads/deployment.yaml`
> - `k8s/workloads/service.yaml`
> - `k8s/workloads/configmap.yaml`

### Commandes
```bash
cd C:/DEVDIR/GITHUB/kube-train
helm create kube-train-chart

# Inspecter le scaffold généré
ls kube-train-chart
ls kube-train-chart/templates

# Nettoyage conseillé : supprimer ce qui n’est pas utile pour le TP
rm kube-train-chart/templates/hpa.yaml
rm kube-train-chart/templates/ingress.yaml
rm kube-train-chart/templates/serviceaccount.yaml
rm kube-train-chart/templates/tests/test-connection.yaml
```

### À configurer dans `kube-train-chart/values.yaml`
On veut piloter au minimum :
- `replicaCount`
- `image.repository`
- `image.tag`
- `image.pullPolicy`

Exemple de base :

```yaml
replicaCount: 1

image:
  repository: europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api
  tag: "latest"
  pullPolicy: IfNotPresent

containerPort: 8080
nameOverride: ""
fullnameOverride: "kube-train"
```

### Exemple de `templates/deployment.yaml`
L’objectif n’est pas de copier-coller tout `k8s/workloads/deployment-gke.yaml`, mais d’en extraire la structure utile et de remplacer les valeurs figées par des expressions Helm.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "kube-train-chart.fullname" . }}
  labels:
    {{- include "kube-train-chart.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ include "kube-train-chart.name" . }}
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: {{ include "kube-train-chart.name" . }}
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      automountServiceAccountToken: false
      containers:
        - name: api-container
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.containerPort }}
```

### Ce qu’il faut migrer depuis `k8s/workloads/deployment-gke.yaml`
- `replicas` → `{{ .Values.replicaCount }}`
- image Artifact Registry → `{{ .Values.image.repository }}` + `{{ .Values.image.tag }}`
- `imagePullPolicy`
- `containerPort: 8080`
- `automountServiceAccountToken: false`
- les labels/selector (à réaligner avec les helpers Helm)

### Vérifications
```bash
helm lint kube-train-chart
helm template kube-train ./kube-train-chart
```

À vérifier dans le rendu :
- le nom de `Deployment` est stable (par exemple `kube-train` avec `fullnameOverride`)
- l’image n’est plus codée en dur
- le nombre de replicas suit `values.yaml`

### Pièges fréquents
- Oublier le point dans `{{ .Values.image.tag }}`
- Laisser les labels du scaffold Helm et les selectors du manifeste historique incohérents
- Reprendre tel quel le nom `kube-train-deployment` sans profiter du nommage Helm basé sur la release

---

## Étape 2 — Ajouter Service + ConfigMap et paramétrer l’environnement

### Objectif
Migrer `k8s/workloads/service.yaml` et `k8s/workloads/configmap.yaml` vers `templates/service.yaml` et `templates/configmap.yaml`, puis injecter les variables d’environnement de façon pilotable par `values.yaml`.

### Commandes
```bash
# Rendu local pur
helm template kube-train ./kube-train-chart

# Simulation d’installation complète
helm install kube-train ./kube-train-chart --dry-run --debug
```

### Exemple de structure dans `values.yaml`
```yaml
service:
  type: ClusterIP
  port: 80

config:
  trainMessage: "🚨 GREVE : Aucun train ne circule."
  springProfilesActive: "postgres,gcp"
  gcpProjectId: "kube-train-project"
  loggingStructuredFormatConsole: "ecs"
  otelServiceName: "kube-train-api"
  otelExporterOtlpEndpoint: "http://otel-collector-service:4317"
  otelExporterOtlpProtocol: "grpc"
  otelMetricsExporter: "none"
  otelLogsExporter: "none"
```

### Exemple de `templates/configmap.yaml`
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "kube-train-chart.fullname" . }}-config
  labels:
    {{- include "kube-train-chart.labels" . | nindent 4 }}
data:
  TRAIN_MESSAGE: {{ .Values.config.trainMessage | quote }}
  SPRING_PROFILES_ACTIVE: {{ .Values.config.springProfilesActive | quote }}
  GCP_PROJECT_ID: {{ .Values.config.gcpProjectId | quote }}
  LOGGING_STRUCTURED_FORMAT_CONSOLE: {{ .Values.config.loggingStructuredFormatConsole | quote }}
  OTEL_SERVICE_NAME: {{ .Values.config.otelServiceName | quote }}
  OTEL_EXPORTER_OTLP_ENDPOINT: {{ .Values.config.otelExporterOtlpEndpoint | quote }}
  OTEL_EXPORTER_OTLP_PROTOCOL: {{ .Values.config.otelExporterOtlpProtocol | quote }}
  OTEL_METRICS_EXPORTER: {{ .Values.config.otelMetricsExporter | quote }}
  OTEL_LOGS_EXPORTER: {{ .Values.config.otelLogsExporter | quote }}
```

### Exemple de `templates/service.yaml`
```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "kube-train-chart.fullname" . }}
  labels:
    {{- include "kube-train-chart.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app.kubernetes.io/name: {{ include "kube-train-chart.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.containerPort }}
      protocol: TCP
```

### Injection dans `templates/deployment.yaml`
Le plus simple est de mixer :
- `envFrom` pour la ConfigMap
- `env` explicite pour les secrets déjà existants

```yaml
envFrom:
  - configMapRef:
      name: {{ include "kube-train-chart.fullname" . }}-config
env:
  - name: TRAIN_API_KEY
    valueFrom:
      secretKeyRef:
        name: kube-train-secrets
        key: API_KEY
```

> Rappel kube-train : `API_KEY` ne mappe pas automatiquement vers `train.api.key`. On conserve donc le renommage explicite `TRAIN_API_KEY`, comme dans `k8s/workloads/deployment-gke.yaml`.

### Vérifications
```bash
helm template kube-train ./kube-train-chart > rendered.yaml
helm install kube-train ./kube-train-chart --dry-run --debug
```

À contrôler dans le rendu :
- la `ConfigMap` reprend bien `TRAIN_MESSAGE`
- le `Service` pointe vers le bon `targetPort`
- le `Deployment` référence la bonne ConfigMap
- les clés sensibles ne sont pas écrites en clair dans `values.yaml`

### Pièges fréquents
- Mettre les secrets dans `values.yaml` au lieu de réutiliser `kube-train-secrets`
- Changer le nom de la ConfigMap sans mettre à jour `envFrom`
- Oublier `quote` sur des valeurs contenant `:` ou des emojis

---

## Étape 3 — Gérer Minikube et GKE avec des values dédiées

### Objectif
Créer deux overlays Helm :
- `values-minikube.yaml`
- `values-gke.yaml`

Le chart reste unique ; seuls les fichiers de valeurs changent selon l’environnement.

### `values-minikube.yaml`
```yaml
replicaCount: 1

image:
  repository: kube-train-api
  tag: "v1"
  pullPolicy: Never

service:
  type: NodePort
  port: 80

config:
  trainMessage: "Mode Minikube : demo locale"
  springProfilesActive: "default"
  gcpProjectId: ""
  loggingStructuredFormatConsole: ""
  otelServiceName: "kube-train-api"
  otelExporterOtlpEndpoint: ""
  otelExporterOtlpProtocol: "grpc"
  otelMetricsExporter: "none"
  otelLogsExporter: "none"
```

### `values-gke.yaml`
```yaml
replicaCount: 2

image:
  repository: europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api
  tag: "8672f7b405ca06bd853ff2ebe0ab0f581f3f67dc"
  pullPolicy: Always

service:
  type: LoadBalancer
  port: 80

resources:
  requests:
    cpu: "200m"
    memory: "512Mi"
    ephemeral-storage: "1Gi"
  limits:
    cpu: "500m"
    memory: "768Mi"

config:
  trainMessage: "🚨 GREVE : Aucun train ne circule."
  springProfilesActive: "postgres,gcp"
  gcpProjectId: "kube-train-project"
  loggingStructuredFormatConsole: "ecs"
  otelServiceName: "kube-train-api"
  otelExporterOtlpEndpoint: "http://otel-collector-service:4317"
  otelExporterOtlpProtocol: "grpc"
  otelMetricsExporter: "none"
  otelLogsExporter: "none"
```

### Complément dans `templates/deployment.yaml`
```yaml
resources:
  {{- toYaml .Values.resources | nindent 10 }}
```

Pense à mettre une valeur par défaut dans `values.yaml` :
```yaml
resources: {}
```

### Installation sur Minikube
```bash
# Important : builder l’image dans le daemon Docker de Minikube
eval $(minikube docker-env)

docker build -t kube-train-api:v1 ./kube-train-api
helm upgrade --install kube-train ./kube-train-chart -f ./kube-train-chart/values-minikube.yaml
```

### Installation sur GKE
```bash
helm upgrade --install kube-train ./kube-train-chart -f ./kube-train-chart/values-gke.yaml
```

### Vérifications
```bash
kubectl get deploy,pods,svc
kubectl describe deployment kube-train
helm get values kube-train
```

À observer :
- Minikube : `imagePullPolicy: Never`, 1 replica
- GKE : `imagePullPolicy: Always`, 2 replicas, ressources présentes
- le chart n’a pas été dupliqué, seuls les fichiers `values-*.yaml` changent

### Pièges fréquents
- Oublier de définir `resources: {}` dans `values.yaml` → erreur de rendu si `toYaml` reçoit `nil`
- Utiliser une image locale en GKE
- Installer Minikube sans avoir construit l’image dans le bon daemon Docker

---

## Étape 4 — Ajouter un CronJob Helm pour une tâche planifiée

### Objectif
Ajouter un template `CronJob` représentant une tâche planifiée d’outbox cleanup. Pour le TP, on utilise un placeholder simple : un conteneur qui vérifie l’endpoint `/actuator/health` toutes les 5 minutes.

### Structure de valeurs proposée
Ajoute dans `values.yaml` :

```yaml
cronjob:
  enabled: true
  schedule: "*/5 * * * *"
  successfulJobsHistoryLimit: 2
  failedJobsHistoryLimit: 1
  backoffLimit: 2
  activeDeadlineSeconds: 60
  image: curlimages/curl:8.8.0
```

### Exemple de `templates/cronjob.yaml`
```yaml
{{- if .Values.cronjob.enabled }}
apiVersion: batch/v1
kind: CronJob
metadata:
  name: {{ include "kube-train-chart.fullname" . }}-outbox-cleanup
spec:
  schedule: {{ .Values.cronjob.schedule | quote }}
  successfulJobsHistoryLimit: {{ .Values.cronjob.successfulJobsHistoryLimit }}
  failedJobsHistoryLimit: {{ .Values.cronjob.failedJobsHistoryLimit }}
  jobTemplate:
    spec:
      backoffLimit: {{ .Values.cronjob.backoffLimit }}
      activeDeadlineSeconds: {{ .Values.cronjob.activeDeadlineSeconds }}
      template:
        spec:
          restartPolicy: OnFailure
          containers:
            - name: outbox-cleanup
              image: {{ .Values.cronjob.image }}
              command:
                - /bin/sh
                - -c
                - >-
                  curl -fsS http://{{ include "kube-train-chart.fullname" . }}:{{ .Values.service.port }}/actuator/health
{{- end }}
```

### Commandes
```bash
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-minikube.yaml
helm upgrade --install kube-train ./kube-train-chart -f ./kube-train-chart/values-minikube.yaml
kubectl get cronjobs
kubectl get jobs
```

### Ce qu’il faut comprendre
- `CronJob.spec.schedule` utilise une syntaxe cron (`"*/5 * * * *"`)
- `backoffLimit` appartient au `Job` sous `jobTemplate.spec`
- `restartPolicy` appartient au template de pod, et doit être `OnFailure` ou `Never`
- un `CronJob` crée des `Job`, qui créent eux-mêmes des pods

### Vérifications
```bash
kubectl describe cronjob kube-train-outbox-cleanup
kubectl get jobs --watch
kubectl logs job/<nom-du-job>
```

À vérifier :
- un `Job` est bien créé à chaque exécution planifiée
- le pod du job termine en `Completed`
- l’historique des jobs reste limité

### Pièges fréquents
- Mettre `restartPolicy: Always` dans un `Job` ou `CronJob`
- Déplacer `backoffLimit` au mauvais niveau YAML
- Oublier de protéger le template avec `{{ if .Values.cronjob.enabled }}`

---

## Étape 5 — Préparer ArgoCD ou démontrer un upgrade atomique

### Objectif
Finaliser le packaging pour un usage GitOps. Deux options :
1. **ArgoCD disponible** : déclarer le chart Helm comme source d’une `Application`
2. **ArgoCD non disponible** : démontrer `helm upgrade --atomic` et le rollback automatique

### Option A — ArgoCD + Helm
Exemple de manifeste `Application` :

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: kube-train
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/samiyc/kube-train.git
    targetRevision: main
    path: kube-train-chart
    helm:
      valueFiles:
        - values-gke.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: default
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

### Vérifications ArgoCD
- le `path` pointe bien vers le dossier du chart
- `valueFiles` référence un fichier réellement présent dans le repo
- le rendu ArgoCD retrouve bien `Deployment`, `Service`, `ConfigMap` et `CronJob`

### Option B — Upgrade atomique sans ArgoCD
Commande nominale :

```bash
helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-gke.yaml \
  --atomic
```

Simulation d’échec : forcer un mauvais tag d’image.

```bash
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-gke.yaml \
  --set image.tag=does-not-exist \
  --atomic
```

### Vérifications côté Helm
```bash
helm history kube-train
helm status kube-train
kubectl rollout status deployment/kube-train
```

À observer :
- si l’upgrade échoue, Helm revient à la révision précédente
- `helm history` montre l’échec puis le rollback
- le service reste disponible sur la version précédente

### Pièges fréquents
- Penser que `--atomic` remplace les probes : sans probes fiables, le rollback peut être tardif ou trompeur
- Oublier que `valueFiles` dans ArgoCD sont relatifs au `path` du chart
- Déployer avec un nom de release différent entre Helm et ArgoCD, ce qui complique le suivi

---

## Résultat attendu en fin de TP

À la fin de J2, tu dois avoir :
- un chart `kube-train-chart/` propre et réutilisable
- un `Deployment` Helm dérivé de `k8s/workloads/deployment-gke.yaml`
- un `Service` et une `ConfigMap` dérivés de `k8s/workloads/service.yaml` et `k8s/workloads/configmap.yaml`
- deux overlays `values-minikube.yaml` et `values-gke.yaml`
- un `CronJob` Helm fonctionnel
- une stratégie de déploiement prête pour ArgoCD ou `helm upgrade --atomic`

### Bonus senior / CKAD
Si tu termines en avance, ajoute un `NOTES.txt` Helm expliquant :
- comment récupérer l’URL du service
- quelle valeur surcharger pour changer `image.tag`
- comment désactiver le `CronJob` via `--set cronjob.enabled=false`
