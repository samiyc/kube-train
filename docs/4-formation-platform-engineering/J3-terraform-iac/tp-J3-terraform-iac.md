# TP J3 — Terraform & Infrastructure as Code sur GCP

**Durée estimée : 2h30-3h30**
**Prérequis** : Terraform 1.8+, `gcloud` authentifié sur `kube-train-project`, bucket GCS autorisé, cluster GKE Autopilot existant

> Objectif du TP : sortir kube-train d'un mode « console + CLI manuel » pour aller vers une infra entièrement décrite en HCL, versionnée, revue en PR et appliquée par pipeline.

---

## Étape 1 — Initialiser le projet Terraform dans `infra/`

### Objectif
Créer le squelette Terraform, configurer le provider Google, externaliser le state dans GCS et valider le bootstrap avec `terraform init`.

### Contexte
Le projet kube-train existe déjà côté GCP : cluster Autopilot, Artifact Registry, Pub/Sub, Cloud SQL, Workload Identity Federation GitHub, etc. On veut maintenant **reprendre proprement la main en IaC** sans casser l'existant.

### Structure de travail recommandée

```text
infra/
├── versions.tf
├── providers.tf
├── variables.tf
├── terraform.tfvars
└── main.tf
```

### HCL minimal

#### `infra/versions.tf`
```hcl
terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  backend "gcs" {
    bucket = "kube-train-terraform-state"
    prefix = "platform-engineering/dev"
  }
}
```

#### `infra/providers.tf`
```hcl
provider "google" {
  project = var.project_id
  region  = var.region
}
```

#### `infra/variables.tf`
```hcl
variable "project_id" {
  type    = string
  default = "kube-train-project"
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "cluster_name" {
  type    = string
  default = "kube-train-cluster"
}
```

### Commandes

```bash
cd C:/DEVDIR/GITHUB/kube-train
mkdir -p infra

# Si le bucket de state n'existe pas encore
# (à faire une seule fois, hors Terraform backend)
gcloud storage buckets create gs://kube-train-terraform-state \
  --project=kube-train-project \
  --location=europe-west1 \
  --uniform-bucket-level-access

gcloud storage buckets update gs://kube-train-terraform-state \
  --versioning

cd infra
terraform init
terraform fmt -recursive
terraform validate
```

### Ce que vous devez vérifier
- `terraform init` télécharge bien le provider `hashicorp/google` ;
- le backend GCS est sélectionné et le state n'est plus local ;
- le bucket de state a le versioning activé ;
- aucun fichier `terraform.tfstate` n'apparaît dans le repo.

### Pièges fréquents
- oublier que **le bucket backend doit exister avant** `terraform init` ;
- confondre backend distant et variables sensibles : le backend stocke le state, pas les secrets applicatifs ;
- croire que le backend GCS remplace le code HCL : le state décrit l'existant, les `.tf` restent la source de vérité déclarative.

---

## Étape 2 — Importer le cluster GKE existant et reproduire l'existant en HCL

### Objectif
Rattacher le cluster `kube-train-cluster` à Terraform, puis écrire une configuration HCL suffisamment fidèle pour obtenir un `terraform plan` sans dérive.

### Contexte
`terraform import` **n'écrit pas le HCL à votre place** : il alimente le state. C'est à vous de traduire ensuite la réalité GCP en code maintenable.

### HCL de départ

#### `infra/gke.tf`
```hcl
data "google_compute_network" "default" {
  name = "default"
}

data "google_compute_subnetwork" "default_euw1" {
  name   = "default"
  region = var.region
}

resource "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region

  enable_autopilot = true

  network    = data.google_compute_network.default.self_link
  subnetwork = data.google_compute_subnetwork.default_euw1.self_link

  deletion_protection = false

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }
}
```

> Si votre cluster actuel n'est pas sur le réseau `default`, remplacez ces `data` sources par les bons objets importés. Le but du TP n'est pas de migrer le réseau, mais de **reproduire fidèlement l'existant**.

### Commandes

```bash
cd C:/DEVDIR/GITHUB/kube-train/infra

terraform import \
  google_container_cluster.main \
  projects/kube-train-project/locations/europe-west1/clusters/kube-train-cluster

terraform state show google_container_cluster.main
terraform plan
```

### Astuce utile
Quand `terraform plan` propose trop de changements, repartez de la sortie de `terraform state show` et recopiez uniquement les attributs réellement nécessaires à une reproduction stable : mode Autopilot, région, canal de release, réseau/sous-réseau, Workload Identity, etc.

### Ce que vous devez vérifier
- la ressource `google_container_cluster.main` apparaît dans le state ;
- `terraform plan` tend vers **No changes** après alignement du HCL ;
- le cluster reste en `enable_autopilot = true` ;
- le `workload_pool` est bien `kube-train-project.svc.id.goog`.

### Pièges fréquents
- mettre un `zone` au lieu d'une `region` pour un cluster régional ;
- oublier `enable_autopilot = true`, ce qui produit un plan complètement faux ;
- vouloir recréer un VPC dédié avant d'avoir obtenu un import propre : commencez par figer l'existant.

---

## Étape 3 — Ajouter Cloud SQL, IAM Workload Identity et Secret Manager

### Objectif
Décrire la base managée PostgreSQL de kube-train, créer l'identité GCP de l'API et lier cette identité au ServiceAccount Kubernetes via Workload Identity.

### Contexte
Aujourd'hui, kube-train utilise déjà Cloud SQL Auth Proxy. On veut donc décrire proprement :
- l'instance Cloud SQL ;
- la base `kube_train` ;
- l'utilisateur `kube_train_user` ;
- le GSA de l'API ;
- les droits `cloudsql.client` et `secretAccessor` ;
- la liaison `google_service_account_iam_member` entre GSA et KSA.

### HCL d'exemple

#### `infra/database.tf`
```hcl
variable "db_password" {
  type      = string
  sensitive = true
}

resource "google_sql_database_instance" "main" {
  name             = "kube-train-db"
  region           = var.region
  database_version = "POSTGRES_15"

  deletion_protection = false

  settings {
    tier = "db-f1-micro"

    backup_configuration {
      enabled = true
    }

    ip_configuration {
      ipv4_enabled = false
    }
  }
}

resource "google_sql_database" "app" {
  name     = "kube_train"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_user" "app" {
  name     = "kube_train_user"
  instance = google_sql_database_instance.main.name
  password = var.db_password
}
```

#### `infra/iam.tf`
```hcl
resource "google_service_account" "kube_train_api" {
  account_id   = "kube-train-api-sa"
  display_name = "GSA pour kube-train-api"
}

resource "google_project_iam_member" "api_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_project_iam_member" "api_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[kube-train/kube-train-api-sa]"
}
```

#### `infra/secrets.tf`
```hcl
resource "google_secret_manager_secret" "api_key" {
  secret_id = "api-key"

  replication {
    auto {}
  }
}
```

> En production, ne stockez jamais les valeurs sensibles en clair dans Git. Gérez-les via variables sensibles, Secret Manager ou import de secrets existants.

### Commandes

```bash
cd C:/DEVDIR/GITHUB/kube-train/infra
terraform plan
terraform apply

gcloud sql instances describe kube-train-db --project=kube-train-project
kubectl get serviceaccount kube-train-api-sa -n kube-train -o yaml
```

### Ce que vous devez vérifier
- l'instance `kube-train-db` est visible dans Cloud SQL ;
- la base `kube_train` et l'utilisateur `kube_train_user` existent ;
- le GSA `kube-train-api-sa@kube-train-project.iam.gserviceaccount.com` existe ;
- la liaison Workload Identity référence bien `kube-train/kube-train-api-sa` ;
- le pod API pourra continuer à parler au Cloud SQL Auth Proxy sans clé JSON.

### Pièges fréquents
- oublier que `google_service_account_iam_member` s'applique **sur le GSA**, pas sur le projet ;
- créer les secrets Terraform dans le state puis committer un `.tfvars` contenant les mots de passe ;
- décrire l'instance Cloud SQL sans penser au coût : `db-f1-micro` suffit pour le TP.

---

## Étape 4 — Ajouter Artifact Registry et Pub/Sub

### Objectif
Faire gérer par Terraform le registre d'images et la messagerie asynchrone utilisés par kube-train en environnement GKE.

### HCL d'exemple

#### `infra/messaging.tf`
```hcl
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "kube-train-repo"
  format        = "DOCKER"
  description   = "Images Docker kube-train"
}

resource "google_pubsub_topic" "reservations" {
  name = "train-reservations"
}

resource "google_pubsub_topic" "reservations_dlq" {
  name = "train-reservations-dlq"
}

resource "google_pubsub_subscription" "notification" {
  name  = "notification-subscription"
  topic = google_pubsub_topic.reservations.name

  ack_deadline_seconds       = 20
  message_retention_duration = "604800s"

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.reservations_dlq.id
    max_delivery_attempts = 5
  }
}
```

### Commandes

```bash
cd C:/DEVDIR/GITHUB/kube-train/infra
terraform plan
terraform apply

gcloud artifacts repositories describe kube-train-repo \
  --location=europe-west1 \
  --project=kube-train-project

gcloud pubsub topics list --project=kube-train-project
gcloud pubsub subscriptions describe notification-subscription \
  --project=kube-train-project
```

### Ce que vous devez vérifier
- le repository `kube-train-repo` existe bien en `europe-west1` ;
- les topics `train-reservations` et `train-reservations-dlq` sont présents ;
- la subscription `notification-subscription` pointe bien vers le topic principal ;
- la DLQ est configurée avec `max_delivery_attempts = 5`.

### Pièges fréquents
- oublier de créer le topic de DLQ avant la subscription ;
- confondre l'ID Terraform d'un topic (`.id`) et son nom logique (`.name`) ;
- provisionner Artifact Registry dans une autre région que celle utilisée par le workflow CI/CD.

---

## Étape 5 — Mettre en place le workflow GitHub Actions Terraform

### Objectif
Passer à un workflow GitOps infra : `terraform plan` en PR, commentaire sur la PR, puis `terraform apply` après merge sur `main`.

### Contexte
Le repo kube-train utilise déjà Workload Identity Federation GitHub pour déployer l'application. On réutilise le même principe pour l'infra : pas de clé JSON stockée dans GitHub.

### Workflow d'exemple : `.github/workflows/terraform-infra.yml`

```yaml
name: terraform-infra

on:
  pull_request:
    paths:
      - 'infra/**'
      - '.github/workflows/terraform-infra.yml'
  push:
    branches:
      - main
    paths:
      - 'infra/**'
      - '.github/workflows/terraform-infra.yml'

permissions:
  contents: read
  pull-requests: write
  id-token: write

jobs:
  plan:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: projects/399291708401/locations/global/workloadIdentityPools/github-pool/providers/github-provider
          service_account: github-actions-sa@kube-train-project.iam.gserviceaccount.com
      - name: Terraform init
        run: terraform -chdir=infra init
      - name: Terraform plan
        run: terraform -chdir=infra plan -no-color > infra/plan.txt
      - name: Commenter la PR
        uses: marocchino/sticky-pull-request-comment@v2
        with:
          path: infra/plan.txt

  apply:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: projects/399291708401/locations/global/workloadIdentityPools/github-pool/providers/github-provider
          service_account: github-actions-sa@kube-train-project.iam.gserviceaccount.com
      - name: Terraform init
        run: terraform -chdir=infra init
      - name: Terraform apply
        run: terraform -chdir=infra apply -auto-approve
```

### Commandes de vérification

```bash
# Ouvrir une PR qui modifie infra/
# → vérifier qu'un commentaire plan apparaît

# Après merge sur main
# → vérifier que le job apply s'exécute

gh run list --workflow terraform-infra
```

### Ce que vous devez vérifier
- le job `plan` ne tourne que sur PR ;
- le job `apply` ne tourne qu'après merge sur `main` ;
- le commentaire de plan permet une vraie revue infra ;
- le workflow utilise `id-token: write` et non une clé JSON statique.

### Pièges fréquents
- faire un `apply` depuis un laptop : on perd l'auditabilité et la reproductibilité ;
- partager le même backend/prefix entre plusieurs environnements sans séparation (`dev`, `prod`) ;
- commenter un plan tronqué ou illisible : utilisez `-no-color` et un commentaire sticky.

---

## Résultat attendu en fin de TP

À la fin de J3, vous devez avoir :
- un dossier `infra/` initialisé ;
- le cluster GKE existant importé et codé proprement ;
- Cloud SQL, IAM Workload Identity, Secret Manager, Artifact Registry et Pub/Sub décrits en Terraform ;
- un workflow GitHub Actions prêt pour un vrai GitOps infra.

> Bonus senior : factorisez ensuite `infra/` en modules (`modules/gke`, `modules/sql`, `modules/messaging`) et ajoutez un environnement `prod` séparé par backend prefix ou workspace.
