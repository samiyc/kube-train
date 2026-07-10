# Helm vs Terraform — est-ce que ton intuition est bonne ?

> Contexte : kube-train F4 — Platform Engineering, avec une infra GCP déclarée dans `infra\` et une application Kubernetes packagée dans `kube-train-chart\`.

---

## 🎯 Réponse courte

**Oui, ton intuition est correcte.**

- **Terraform** remplace en grande partie les commandes `gcloud` manuelles pour créer l'infrastructure cloud.
- **Helm** évite de dupliquer/désynchroniser les YAML Kubernetes en permettant des valeurs différentes par environnement.

La précision importante est celle-ci :

```text
Terraform = ce qui permet au cluster et aux services cloud d'exister
Helm      = ce qui déploie l'application dans le cluster
```

Ou encore :

- **Terraform** répond à : *"Sur quelle infrastructure mon application va-t-elle tourner ?"*
- **Helm** répond à : *"Quels objets Kubernetes mon application installe-t-elle dans ce cluster ?"*

---

## 1. Terraform = IaC pour l'INFRASTRUCTURE

Terraform décrit l'état attendu de l'infrastructure GCP dans des fichiers HCL (`.tf`), puis calcule le delta avec l'état réel.

Dans kube-train, Terraform est dans :

```text
C:\DEVDIR\GITHUB\kube-train\infra\
```

Exemples réels :

| Fichier | Ce qu'il gère |
|---------|---------------|
| `infra\gke.tf` | Cluster **GKE Autopilot** `kube-train-cluster` en `europe-west1` |
| `infra\main.tf` | Instance **Cloud SQL PostgreSQL 15** `kube-train-db`, base `kube_train`, user `kube_train_user` |
| `infra\messaging.tf` | **Artifact Registry**, topics Pub/Sub `train-reservations`, `train-reservations-dlq`, subscription `notification-subscription` |
| `infra\iam.tf` | Service accounts, rôles IAM, Workload Identity |
| `infra\apis.tf` | APIs GCP nécessaires au mesh / networking |
| `infra\providers.tf` | Provider `hashicorp/google` |
| `infra\versions.tf` | Version Terraform, provider Google, backend GCS `kube-train-terraform-state` |
| `infra\variables.tf` / `terraform.tfvars` | Variables projet/région/cluster/password |

Donc au lieu de lancer à la main une suite de commandes du type :

```bash
gcloud container clusters create-auto ...
gcloud sql instances create ...
gcloud pubsub topics create ...
gcloud projects add-iam-policy-binding ...
```

tu décris l'état cible :

```hcl
resource "google_container_cluster" "main" {
  name             = var.cluster_name
  location         = var.region
  enable_autopilot = true
}
```

Puis Terraform fait :

```bash
terraform plan
terraform apply
terraform destroy
```

### 🎯 Le point clé : le state

Terraform maintient un **state** (`tfstate`) pour savoir ce qu'il a créé.

Dans ce repo, le backend est GCS :

```hcl
backend "gcs" {
  bucket = "kube-train-terraform-state"
  prefix = "platform-engineering/dev"
}
```

Grâce au state, Terraform peut dire :

- cette ressource existe déjà ;
- celle-ci doit être modifiée ;
- celle-ci doit être détruite ;
- celle-ci a dérivé par rapport au code.

---

## 2. Helm = package manager pour les APPLICATIONS Kubernetes

Helm sert à packager et paramétrer les manifests Kubernetes.

Dans kube-train, le chart est dans :

```text
C:\DEVDIR\GITHUB\kube-train\kube-train-chart\
```

Exemples réels :

| Fichier | Rôle |
|---------|------|
| `kube-train-chart\Chart.yaml` | Métadonnées du chart `kube-train-chart`, version `0.1.0`, appVersion `4.0.0` |
| `kube-train-chart\values.yaml` | Valeurs par défaut |
| `kube-train-chart\values-minikube.yaml` | Overlay local Minikube |
| `kube-train-chart\values-gke.yaml` | Overlay GKE |
| `kube-train-chart\templates\deployment.yaml` | Template du Deployment API |
| `kube-train-chart\templates\service.yaml` | Template du Service |
| `kube-train-chart\templates\configmap.yaml` | Template du ConfigMap |
| `kube-train-chart\templates\cronjob.yaml` | Template du CronJob optionnel |

Le template contient des variables :

```yaml
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
imagePullPolicy: {{ .Values.image.pullPolicy }}
replicas: {{ .Values.replicaCount }}
type: {{ .Values.service.type }}
```

Puis chaque environnement fournit ses valeurs.

### Exemple Minikube

Dans `kube-train-chart\values-minikube.yaml` :

```yaml
image:
  repository: kube-train-api
  tag: "v5"
  pullPolicy: Never

service:
  type: NodePort

cloudSqlProxy:
  enabled: false
```

### Exemple GKE

Dans `kube-train-chart\values-gke.yaml` :

```yaml
image:
  repository: europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api
  tag: "latest"
  pullPolicy: IfNotPresent

service:
  type: LoadBalancer

cloudSqlProxy:
  enabled: true
  connectionName: "kube-train-project:europe-west1:kube-train-db"
```

🎯 C'est exactement ton idée : **un seul modèle de Deployment/Service/ConfigMap, plusieurs fichiers de valeurs selon l'environnement**.

---

## 3. Ce que Helm remplace dans kube-train

Avant Helm, les manifests bruts sont dans :

```text
C:\DEVDIR\GITHUB\kube-train\k8s\
```

Exemples :

| Manifest brut | Problème résolu par Helm |
|---------------|--------------------------|
| `k8s\workloads\deployment.yaml` | Deployment Minikube avec image locale `kube-train-api:v5`, `imagePullPolicy: Never` |
| `k8s\workloads\deployment-gke.yaml` | Deployment GKE avec image Artifact Registry, Cloud SQL Proxy, variables GCP |
| `k8s\workloads\service.yaml` | Service exposé en `LoadBalancer` ou `NodePort` selon le contexte |
| `k8s\workloads\configmap.yaml` | Variables non sensibles comme `TRAIN_MESSAGE` |

Sans Helm, chaque différence d'environnement pousse à copier/coller des YAML.

Avec Helm :

```bash
helm upgrade --install kube-train .\kube-train-chart -f .\kube-train-chart\values-minikube.yaml
helm upgrade --install kube-train .\kube-train-chart -f .\kube-train-chart\values-gke.yaml --set image.tag=$SHA --atomic
```

Le chart API remplace principalement les manifests applicatifs API : Deployment, Service, ConfigMap, CronJob. Certains objets du repo restent volontairement séparés selon le besoin : RBAC, Istio, Gatekeeper, notification service, observabilité, etc.

---

## 4. Tableau comparatif

| Critère | Terraform | Helm |
|---------|-----------|------|
| **Couche** | Infrastructure cloud | Application Kubernetes |
| **Question** | *Qu'est-ce qui existe sur GCP ?* | *Qu'est-ce qui tourne dans le cluster ?* |
| **Dans kube-train** | GKE, Cloud SQL, Pub/Sub, IAM, APIs, Artifact Registry | Deployment API, Service, ConfigMap, CronJob, valeurs Minikube/GKE |
| **Langage** | HCL (`.tf`) | YAML + templates Go (`{{ .Values... }}`) |
| **État** | `tfstate` dans GCS | Release Helm stockée dans Kubernetes, souvent via Secret |
| **Cycle** | `plan` → `apply` → `destroy` | `install` / `upgrade` → `rollback` / `uninstall` |
| **Idempotence** | Native via state + plan | Idempotence de release : Helm recalcule les manifests et upgrade |
| **Rollback** | Possible mais plutôt via retour de code + apply | Natif avec `helm history` / `helm rollback` |
| **Secrets** | Peut créer/gérer des secrets cloud, attention au state | Peut référencer des Secrets K8s ; éviter de mettre les secrets en clair dans values |
| **Multi-env** | Variables, `*.tfvars`, workspaces, backends séparés | `values-dev.yaml`, `values-recette.yaml`, `values-prod.yaml` |
| **Destruction** | Détruit l'infra : cluster, DB, topics, IAM... | Désinstalle les objets applicatifs de la release |

---

## 5. La frontière dans kube-train

### Terraform crée le socle

```text
GCP Project
  ├─ GKE Autopilot cluster
  ├─ Cloud SQL PostgreSQL
  ├─ Pub/Sub topics/subscription
  ├─ Artifact Registry
  ├─ IAM roles
  └─ Workload Identity
```

### Helm / kubectl déploie dedans

```text
GKE cluster
  ├─ Deployment kube-train-api
  ├─ Service kube-train
  ├─ ConfigMap kube-train-config
  ├─ Secrets Kubernetes existants
  ├─ Ingress
  ├─ Istio policies
  └─ notification-service
```

🎯 Formule simple :

```text
Terraform construit le terrain, les rails et la gare.
Helm installe les trains, les horaires et les panneaux dans la gare.
```

Terraform peut techniquement déployer du Kubernetes via les providers `kubernetes` ou `helm`. Mais dans ce repo, le choix est plus clair :

- Terraform reste centré sur **l'infra GCP** ;
- Helm/kubectl restent centrés sur **les workloads Kubernetes**.

C'est une bonne séparation pour apprendre, maintenir et débugger.

---

## 6. Dev / recette / prod : deux axes différents

Tu as bien identifié le besoin Helm :

```text
même application Kubernetes
mais valeurs différentes selon l'environnement
```

Exemple :

| Valeur | Minikube | GKE |
|--------|----------|-----|
| Image | `kube-train-api:v5` | Artifact Registry |
| Pull policy | `Never` | `IfNotPresent` |
| Service | `NodePort` | `LoadBalancer` |
| Profil Spring | `postgres` | `postgres,gcp` |
| Cloud SQL Proxy | désactivé | activé |
| Message train | `Mode Minikube — démo locale` | `🚨 GREVE : Aucun train ne circule.` |

Pour dev/recette/prod, tu pourrais avoir :

```text
kube-train-chart\values-dev.yaml
kube-train-chart\values-recette.yaml
kube-train-chart\values-prod.yaml
```

Mais Terraform a aussi son multi-env, sur un autre axe :

```text
infra dev     = petit cluster, petite DB, budget bas
infra recette = proche prod, données de test
infra prod    = HA, sauvegardes, quotas, alertes
```

Avec Terraform, on gérerait ça plutôt avec :

```text
infra\envs\dev.tfvars
infra\envs\recette.tfvars
infra\envs\prod.tfvars
```

ou avec des workspaces/backends séparés.

### 🎯 À retenir

| Besoin multi-env | Outil naturel |
|------------------|---------------|
| Changer le type de Service, l'image, les probes, les variables applicatives | Helm |
| Changer la taille de la DB, la région, le cluster, les rôles IAM, les topics Pub/Sub | Terraform |

---

## 7. Ne pas les opposer : ils sont complémentaires

Terraform et Helm ne font pas le même métier.

Ils s'empilent :

```text
Terraform
  └─ crée le cluster GKE + services cloud
       └─ Helm
            └─ déploie l'application Kubernetes dans ce cluster
```

Analogie ELI5 :

```text
Terraform = construire la maison : terrain, murs, eau, électricité.
Helm      = installer les meubles : cuisine, lit, table, décoration.
```

Tu peux changer les meubles sans reconstruire la maison.  
Tu peux reconstruire la maison, mais il faudra ensuite réinstaller les meubles.

---

## 8. Checklist : quand utiliser quoi ?

### Utilise Terraform si tu veux...

- créer ou supprimer un cluster GKE ;
- créer Cloud SQL ;
- créer Pub/Sub ;
- créer Artifact Registry ;
- activer des APIs GCP ;
- gérer IAM / Workload Identity ;
- gérer le coût infra avec `terraform destroy` ;
- remplacer des commandes `gcloud` répétitives par du déclaratif versionné.

### Utilise Helm si tu veux...

- déployer l'API dans Kubernetes ;
- varier les valeurs entre Minikube, GKE, dev, recette, prod ;
- éviter la duplication de `deployment.yaml`, `deployment-gke.yaml`, etc. ;
- packager Deployment + Service + ConfigMap ensemble ;
- faire un upgrade applicatif reproductible ;
- faire un rollback de release ;
- partager une installation standardisée.

### Utilise plutôt `kubectl` brut si...

- tu apprends ou débugges un objet précis ;
- tu appliques un manifeste isolé ;
- tu fais un test rapide ;
- le templating Helm ajouterait plus de complexité que de valeur.

---

## Conclusion

Ton modèle mental est bon :

> **Terraform évite les commandes cloud manuelles en déclarant l'infra.**  
> **Helm évite la duplication des YAML Kubernetes en déclarant un package applicatif paramétrable.**

La version encore plus précise pour kube-train :

```text
Terraform = GKE + Cloud SQL + Pub/Sub + IAM + APIs
Helm      = Deployment + Service + ConfigMap + valeurs Minikube/GKE
```

Donc oui : **Terraform est l'IaC du socle cloud, Helm est le packaging paramétrable de l'application Kubernetes.**
