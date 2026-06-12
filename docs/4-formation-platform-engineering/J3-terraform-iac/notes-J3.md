# Notes J3 — Terraform & Infrastructure as Code (GCP)

> Formation F4 — Platform Engineering  
> Objectif : provisionner l'intégralité de l'infrastructure kube-train via Terraform — zéro clic Console.

---

## 1. Terraform — Concepts fondamentaux

### Le cycle de vie standard

```
terraform init → terraform plan → terraform apply → terraform destroy
```

| Commande | Rôle |
|---|---|
| `terraform init` | Télécharge les providers, initialise le backend (GCS), prépare le workspace |
| `terraform validate` | Vérifie la syntaxe HCL sans appel réseau |
| `terraform fmt` | Reformate les fichiers `.tf` selon les conventions officielles |
| `terraform plan` | Compare state ↔ infra réelle ↔ config HCL — produit un diff lisible |
| `terraform apply` | Applique le plan sur l'infrastructure réelle |
| `terraform destroy` | Supprime toutes les ressources décrites dans le state |
| `terraform output` | Affiche les valeurs déclarées dans les blocs `output` |

### Les 5 blocs principaux HCL

```hcl
# 1 — provider : plugin d'accès à un cloud
provider "google" {
  project = var.project_id
  region  = var.region
}

# 2 — resource : objet créé et géré par Terraform
resource "google_container_cluster" "main" {
  name     = "kube-train-cluster"
  location = var.region
}

# 3 — data : objet LU dans l'infra existante (pas géré)
data "google_compute_network" "default" {
  name = "default"
}

# 4 — variable : entrée externe (CLI, .tfvars, CI)
variable "project_id" {
  type    = string
  default = "kube-train-project"
}

# 5 — output : valeur exposée après apply (URL, emails, noms)
output "cluster_endpoint" {
  value = google_container_cluster.main.endpoint
}
```

### locals — dérivations internes

```hcl
locals {
  app_prefix = "${var.project_id}-kube-train"
  sa_email   = google_service_account.kube_train_api.email
}

# Utilisation : local.app_prefix (pas var.app_prefix)
```

**Règle** : `variable` = entrée externe, `locals` = calcul interne, `output` = exposition externe.  
Ne jamais mettre des IDs sensibles dans les `locals` pour "les cacher" — le state les stocke de toute façon.

---

## 2. State Terraform & Backend GCS

### Le rôle du state

Le state (`terraform.tfstate`) est la **mémoire de Terraform** : il mappe chaque ressource HCL à son identifiant réel dans le cloud.

Sans state :
- Terraform ne saurait pas que `google_container_cluster.main` correspond au cluster `kube-train-cluster`
- `terraform plan` proposerait de tout recréer à chaque fois

### Backend GCS — state distant

Par défaut, le state est stocké **localement** (`terraform.tfstate`). Problèmes en équipe :
- Deux personnes appliquent en même temps → corruption
- Le fichier contient des secrets en clair → risque de commit

**Solution** : backend GCS

```hcl
# infra/versions.tf
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
    prefix = "platform-engineering/dev"   # permet de séparer dev/prod dans le même bucket
  }
}
```

**Avantages du backend GCS** :
| Feature | Bénéfice |
|---|---|
| State centralisé | Toute l'équipe (et la CI) voient le même état |
| Verrouillage (lock) | Un seul `terraform apply` à la fois — évite les états corrompus |
| Versioning GCS | Historique + rollback en cas de state corrompu |

### .gitignore obligatoire

```gitignore
# Terraform
**/.terraform/
*.tfstate
*.tfstate.backup
*.tfvars          # peut contenir des mots de passe
.terraform.lock.hcl  # optionnel, mais souvent commité pour figer les versions
```

**Règle d'or** : Ne jamais committer `terraform.tfstate`. Il contient les secrets en clair (mots de passe Cloud SQL, clés API, etc.).

---

## 3. terraform import

### Ce que `terraform import` fait

`terraform import` **rattache une ressource existante au state Terraform**. Il ne génère PAS le code HCL.

```
Sans import  →  Terraform ne connaît pas la ressource → plan propose de la créer
Après import →  Terraform connaît la ressource → plan compare HCL ↔ réalité
```

### Workflow d'import

```bash
# 1. Écrire un bloc resource vide (ou minimal) dans le HCL
# 2. Importer la ressource dans le state
terraform import google_container_cluster.main \
  projects/kube-train-project/locations/europe-west1/clusters/kube-train-cluster

# 3. Inspecter ce que Terraform a appris
terraform state show google_container_cluster.main

# 4. Copier les attributs importants dans le HCL jusqu'à obtenir :
terraform plan
# → No changes. Infrastructure is up-to-date.
```

### Syntaxe des IDs d'import GCP

| Ressource | Format de l'ID d'import |
|---|---|
| `google_container_cluster` | `projects/{project}/locations/{region}/clusters/{name}` |
| `google_sql_database_instance` | `projects/{project}/instances/{name}` |
| `google_service_account` | `projects/{project}/serviceAccounts/{email}` |
| `google_artifact_registry_repository` | `projects/{project}/locations/{region}/repositories/{name}` |
| `google_pubsub_topic` | `projects/{project}/topics/{name}` |

### Piège fréquent

`terraform import` ne produit pas un HCL propre. Après import, `terraform plan` montrera souvent des dizaines de changements car les attributs par défaut diffèrent. L'objectif est de raffiner le HCL jusqu'à convergence (`No changes`).

---

## 4. Lire un terraform plan

### Les symboles

```
+ resource "google_pubsub_topic" "reservations"  → Création
~ resource "google_sql_database_instance" "main"  → Modification
- resource "google_artifact_registry_repository"  → Destruction

# Forces replacement (destroy + create en séquence)
-/+ resource "google_container_cluster" "main"
  ~ name = "old-name" → "new-name"  # forces replacement
```

| Symbole | Signification |
|---|---|
| `+` | Création d'une nouvelle ressource |
| `~` | Modification d'une ressource existante (in-place) |
| `-` | Destruction d'une ressource |
| `-/+` | Remplacement forcé (destroy + create) — attention : destructif |
| `<=` | Lecture d'une `data source` |

### known after apply

```hcl
+ endpoint = (known after apply)
```

Certains attributs ne peuvent être connus qu'après la création (IP publique, endpoint GKE, etc.). Normal.

---

## 5. Variables, locals, outputs — Bonnes pratiques

### Fichiers recommandés

```
infra/
├── versions.tf        # terraform + required_providers + backend
├── providers.tf       # provider "google" {}
├── variables.tf       # déclarations variable {}
├── terraform.tfvars   # valeurs (NE PAS COMMITTER si contient des secrets)
├── main.tf            # ou fichiers thématiques : gke.tf, database.tf, iam.tf...
└── outputs.tf         # output {}
```

### variables.tf

```hcl
variable "project_id" {
  type        = string
  description = "GCP project ID"
  default     = "kube-train-project"
}

variable "db_password" {
  type      = string
  sensitive = true     # masqué dans les logs, chiffré dans le state
}
```

**`sensitive = true`** : Terraform masque la valeur dans `terraform plan` et `terraform apply`. La valeur est toujours dans le state (chiffré côté GCS), mais n'apparaît plus dans les logs CI.

### terraform.tfvars

```hcl
# terraform.tfvars — NE PAS COMMITTER
project_id  = "kube-train-project"
db_password = "super-secret-password"
```

En CI/CD : passer les secrets via variables d'environnement `TF_VAR_db_password=${{ secrets.DB_PASSWORD }}`.

### outputs.tf

```hcl
output "cluster_endpoint" {
  value       = google_container_cluster.main.endpoint
  description = "GKE cluster control plane endpoint"
}

output "sql_connection_name" {
  value       = google_sql_database_instance.main.connection_name
  description = "Cloud SQL connection name pour le proxy"
}

output "artifact_registry_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}
```

---

## 6. Ressources GCP clés pour kube-train

### GKE Autopilot

```hcl
resource "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region             # régional (3 zones) — pas de zone unique

  enable_autopilot = true           # obligatoire, sinon plan complètement faux

  network    = data.google_compute_network.default.self_link
  subnetwork = data.google_compute_subnetwork.default_euw1.self_link

  deletion_protection = false       # permet terraform destroy en formation

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }
}
```

**Piège** : `location` = région (`europe-west1`) pour un cluster régional, pas `europe-west1-b` (zone). Autopilot est toujours régional.

### Cloud SQL PostgreSQL

```hcl
resource "google_sql_database_instance" "main" {
  name             = "kube-train-db"
  region           = var.region
  database_version = "POSTGRES_15"
  deletion_protection = false

  settings {
    tier = "db-f1-micro"   # micro-instance, suffisant pour formation

    backup_configuration {
      enabled = true
    }

    ip_configuration {
      ipv4_enabled = false  # pas d'IP publique — accès via Cloud SQL Auth Proxy
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

### IAM & Workload Identity

```hcl
# GSA (Google Service Account) pour l'API
resource "google_service_account" "kube_train_api" {
  account_id   = "kube-train-api-sa"
  display_name = "GSA pour kube-train-api"
}

# Droits Cloud SQL
resource "google_project_iam_member" "api_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

# Droits Secret Manager
resource "google_project_iam_member" "api_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

# Liaison KSA → GSA (Workload Identity)
resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  # Format : serviceAccount:{project}.svc.id.goog[{namespace}/{ksa_name}]
  member = "serviceAccount:${var.project_id}.svc.id.goog[kube-train/kube-train-api-sa]"
}
```

**Différence `google_project_iam_member` vs `google_service_account_iam_member`** :
- `google_project_iam_member` → ajoute un rôle **sur le projet** (Cloud SQL client, Secret Accessor)
- `google_service_account_iam_member` → ajoute un rôle **sur un GSA spécifique** (workloadIdentityUser)

### Artifact Registry + Pub/Sub

```hcl
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "kube-train-repo"
  format        = "DOCKER"
}

resource "google_pubsub_topic" "reservations" {
  name = "train-reservations"
}

resource "google_pubsub_topic" "reservations_dlq" {
  name = "train-reservations-dlq"
}

resource "google_pubsub_subscription" "notification" {
  name  = "notification-subscription"
  topic = google_pubsub_topic.reservations.name  # référence Terraform (pas hardcoded)

  ack_deadline_seconds       = 20
  message_retention_duration = "604800s"   # 7 jours

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.reservations_dlq.id
    max_delivery_attempts = 5
  }
}
```

**Piège Pub/Sub** : créer le topic DLQ **avant** la subscription — sinon `terraform apply` échoue sur la référence à `dead_letter_topic`.  
Ici ce n'est pas un problème car Terraform résout l'ordre grâce aux références inter-ressources.

---

## 7. Workload Identity Federation — Mécanisme complet

### Le problème sans Workload Identity

Sans WIF, le pod a besoin d'une **clé JSON** de service account GCP dans un Secret K8s. Risques :
- Clé longue durée de vie (jusqu'à révocation manuelle)
- Clé exportable, copiable, potentiellement committée
- Rotation manuelle fastidieuse

### Avec Workload Identity : pas de clé JSON

```
Pod K8s (KSA: kube-train-api-sa)
  → GKE projette un token JWT OIDC signé par le cluster
  → Ce token est échangé contre un token GCP IAM à courte durée de vie
  → Le GSA kube-train-api-sa@... agit à la place du pod
```

### Configuration Terraform complète

```hcl
# 1. Activer Workload Identity sur le cluster
resource "google_container_cluster" "main" {
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }
}

# 2. Lier KSA → GSA (côté GCP IAM)
resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[kube-train/kube-train-api-sa]"
}
```

### Configuration Kubernetes côté KSA (hors Terraform)

```yaml
# Dans le Deployment (Helm values-gke.yaml)
serviceAccountName: kube-train-api-sa

# Le ServiceAccount doit porter cette annotation :
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-train-api-sa
  annotations:
    iam.gke.io/gcp-service-account: kube-train-api-sa@kube-train-project.iam.gserviceaccount.com
```

Cette annotation est gérée via le chart Helm (`serviceAccount.annotations` dans les values GKE).

---

## 8. GitOps Terraform — Workflow PR → plan → apply

### Architecture du pipeline

```
Feature branch → PR → plan (commentaire) → Review → Merge main → apply
```

### Pourquoi ce workflow ?

| Pratique | Raison |
|---|---|
| `plan` sur PR | Les reviewers voient le diff infra avant merge — comme un diff code |
| `apply` sur merge main | Seul le code validé par PR est appliqué — auditabilité |
| Workload Identity (pas de clé JSON) | Pas de secret statique dans GitHub — token éphémère |
| Backend GCS partagé | State unique — pas de divergence entre laptops |

### Workflow GitHub Actions

```yaml
# .github/workflows/terraform-infra.yml
on:
  pull_request:
    paths: ['infra/**']
  push:
    branches: [main]
    paths: ['infra/**']

permissions:
  contents: read
  pull-requests: write   # pour poster le commentaire plan
  id-token: write        # OBLIGATOIRE pour Workload Identity Federation

jobs:
  plan:
    if: github.event_name == 'pull_request'
    steps:
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: projects/399291708401/locations/global/workloadIdentityPools/github-pool/providers/github-provider
          service_account: github-actions-sa@kube-train-project.iam.gserviceaccount.com
      - run: terraform -chdir=infra init
      - run: terraform -chdir=infra plan -no-color > infra/plan.txt
      - uses: marocchino/sticky-pull-request-comment@v2
        with:
          path: infra/plan.txt

  apply:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    steps:
      - run: terraform -chdir=infra init
      - run: terraform -chdir=infra apply -auto-approve
```

**Points clés** :
- `id-token: write` → permission OIDC pour Workload Identity Federation GitHub
- `-chdir=infra` → exécute Terraform depuis le dossier `infra/` (alternative à `cd infra`)
- `sticky-pull-request-comment` → met à jour le même commentaire sur chaque push de la PR (pas de spam)
- `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` → apply uniquement sur merge

### Isolation par environnement

```hcl
# dev → prefix "platform-engineering/dev"
# prod → prefix "platform-engineering/prod"

backend "gcs" {
  bucket = "kube-train-terraform-state"
  prefix = "platform-engineering/dev"
}
```

Même bucket, préfixes différents = states complètement isolés.

---

## 9. Structure de fichiers recommandée

```
infra/
├── versions.tf          # terraform {} + required_providers + backend
├── providers.tf         # provider "google" {}
├── variables.tf         # variable {} (toutes les entrées)
├── terraform.tfvars     # valeurs (à .gitignore si secrets)
├── gke.tf               # google_container_cluster
├── database.tf          # google_sql_database_instance + database + user
├── iam.tf               # google_service_account + iam_member + workload_identity
├── messaging.tf         # google_artifact_registry + pubsub topics + subscription
├── secrets.tf           # google_secret_manager_secret
└── outputs.tf           # output {}
```

**Approche module** (bonus senior) :
```
infra/
├── modules/
│   ├── gke/         → module réutilisable cluster GKE
│   ├── sql/         → module réutilisable Cloud SQL
│   └── messaging/   → module réutilisable Pub/Sub + Artifact Registry
├── environments/
│   ├── dev/         → appelle les modules avec les vars dev
│   └── prod/        → appelle les modules avec les vars prod
```

---

## 10. Bonnes pratiques de sécurité IaC

| Pratique | Mise en œuvre |
|---|---|
| Pas de secrets en clair dans les `.tf` | `variable "db_password" { sensitive = true }` |
| Pas de `.tfvars` avec secrets dans Git | `.gitignore` obligatoire |
| `deletion_protection = false` uniquement en formation | En prod : `true` sur Cloud SQL et GKE |
| Backend distant + locking | Backend GCS avec versioning activé |
| Drift detection | `terraform plan` régulier en CI — une sortie non-vide = dérive |
| Politique de moindre privilège | IAM member ciblé (pas `roles/owner` pour `github-actions-sa`) |
| `terraform destroy` en fin de journée | Budget ≤ 5€/jour sur kube-train |

---

## Rappel — Prix GCP des ressources J3

| Ressource | Coût estimé |
|---|---|
| GKE Autopilot (2 pods) | ~3-4 €/jour |
| Cloud SQL db-f1-micro (actif) | ~0.25 €/jour |
| Artifact Registry | ~0 € (< 500 MB) |
| Pub/Sub | ~0 € (< 10 GB/mois) |
| GCS bucket state | ~0.02 €/mois |
| **Total journée** | **~3.5 €** (en stoppant Cloud SQL le soir) |

```bash
# En fin de journée : arrêter Cloud SQL pour économiser
gcloud sql instances patch kube-train-db \
  --activation-policy=NEVER \
  --project=kube-train-project

# Ou tout détruire avec Terraform :
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra
terraform destroy
```
