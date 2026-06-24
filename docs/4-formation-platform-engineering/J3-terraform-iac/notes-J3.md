# Notes J3 — Terraform & Infrastructure as Code (GCP)

> Formation F4 — Platform Engineering  
> Objectif : provisionner l'intégralité de l'infrastructure kube-train via Terraform — zéro clic Console.

---

## Glossaire — Acronymes J3

| Acronyme | Signification | Contexte |
|---|---|---|
| IaC | Infrastructure as Code | Paradigme : décrire, versionner et déployer l'infrastructure en code |
| HCL | HashiCorp Configuration Language | Langage déclaratif de Terraform — fichiers `.tf` |
| GCS | Google Cloud Storage | Service de stockage objet GCP — backend Terraform pour le state |
| KSA | Kubernetes ServiceAccount | Identité pod côté K8s dans la liaison Workload Identity |
| GSA | Google Service Account | Identité IAM côté GCP dans la liaison Workload Identity |
| OIDC | OpenID Connect | Protocole d'authentification sur OAuth 2.0 — utilisé par Workload Identity Federation |
| WIF | Workload Identity Federation | Mécanisme GCP : échange d'un token K8s contre un token GCP sans clé JSON |
| IAM | Identity and Access Management | Service GCP de gestion des identités et des droits d'accès |
| VPC | Virtual Private Cloud | Réseau privé virtuel isolé dans GCP |
| DLQ | Dead Letter Queue | File de renvoi des messages non livrables — Pub/Sub `train-reservations-dlq` |
| CI/CD | Continuous Integration / Continuous Delivery | Pipeline automatisé — workflow GitHub Actions Terraform |
| GKE | Google Kubernetes Engine | Service Kubernetes managé sur Google Cloud Platform |
| PR | Pull Request | Demande de fusion de code — déclenche `terraform plan` dans le workflow GitOps |
| SQL | Structured Query Language | Langage de requête base de données — Cloud SQL = PostgreSQL managé GCP |

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

> 📖 **Documentation officielle GKE** : [Workload Identity Federation for GKE](https://cloud.google.com/kubernetes-engine/docs/concepts/workload-identity)

---

#### ELI5 — Ce que fait concrètement iam.tf

**Le problème de base** : le pod Kubernetes a besoin d'accéder à des services GCP (Cloud SQL, Secret Manager, Pub/Sub). Comment s'authentifie-t-il sans stocker une clé secrète dans le cluster ?

**L'analogie** : imagine un employé (le pod) qui veut entrer dans des bâtiments sécurisés (services GCP).

- Sans WIF : l'employé doit porter une **clé physique** (fichier JSON) dans sa poche. Si elle est volée, l'accès est compromis jusqu'à révocation manuelle.
- Avec WIF : l'employé montre son **badge entreprise** (token Kubernetes) à un guichet d'échange. Le guichet vérifie l'identité et remet un **badge visiteur temporaire** (token GCP, valide ~1h) qui s'annule automatiquement.

**Les 4 ressources Terraform de iam.tf et leur rôle** :

```
iam.tf contient 4 types de blocs distincts :

① google_service_account          → Crée l'identité GCP (le "badge GCP")
② google_project_iam_member       → Donne des droits à ce badge sur les services GCP
③ google_service_account_iam_member → Autorise le pod K8s à utiliser ce badge (le pont KSA→GSA)
④ (annotation K8s, hors Terraform) → Dit au pod quel badge GCP utiliser
```

**Flux complet, étape par étape** :

```
1. GKE émet automatiquement un token JWT signé pour chaque pod
   (basé sur son KSA : kube-train-api-sa dans le namespace default)

2. Le Cloud SQL Auth Proxy (sidecar) lit ce token JWT

3. Le proxy appelle le GCP Security Token Service (STS) :
   "J'ai ce token K8s — donne-moi un token GCP pour kube-train-api-sa@kube-train-project.iam.gserviceaccount.com"

4. GCP STS vérifie :
   → "Est-ce que kube-train-project.svc.id.goog[default/kube-train-api-sa]
      a le rôle roles/iam.workloadIdentityUser sur ce GSA ?"
   → OUI (défini par google_service_account_iam_member dans iam.tf)

5. GCP STS émet un token GCP court-terme (~1h) pour le GSA

6. Le proxy utilise ce token pour se connecter à Cloud SQL

7. Cloud SQL vérifie :
   → "Est-ce que kube-train-api-sa@... a roles/cloudsql.client sur ce projet ?"
   → OUI (défini par google_project_iam_member dans iam.tf)

8. Connexion autorisée ✅
```

**Schéma des 3 couches IAM** :

```
┌─────────────────────────────────────────────────────────────┐
│  Kubernetes (namespace: default)                            │
│  ServiceAccount: kube-train-api-sa                          │
│  Annotation: iam.gke.io/gcp-service-account=kube-train-...  │ ← dit QUEL badge GCP utiliser
└────────────────────────┬────────────────────────────────────┘
                         │ token JWT K8s
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  GCP IAM — WIF binding (google_service_account_iam_member)  │
│  Autorise : svc.id.goog[default/kube-train-api-sa]          │
│  Rôle : roles/iam.workloadIdentityUser                      │ ← pont KSA → GSA
│  Sur : kube-train-api-sa@kube-train-project.iam...          │
└────────────────────────┬────────────────────────────────────┘
                         │ token GCP court-terme
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  GCP IAM — Project bindings (google_project_iam_member)     │
│  GSA : kube-train-api-sa@kube-train-project.iam...          │
│  Droits : roles/cloudsql.client                             │ ← ce que le badge autorise
│           roles/secretmanager.secretAccessor                │
│           roles/pubsub.publisher                            │
└─────────────────────────────────────────────────────────────┘
```

---

#### Le HCL complet commenté

```hcl
# ① Crée l'identité GCP de l'API — "le badge GCP"
resource "google_service_account" "kube_train_api" {
  account_id   = "kube-train-api-sa"
  display_name = "GSA pour kube-train-api"
}

# ② Droits du badge sur les services GCP
# Ces 3 ressources disent : "ce badge GCP peut faire X sur le projet"
resource "google_project_iam_member" "api_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"   # se connecter à Cloud SQL via Auth Proxy
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_project_iam_member" "api_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"   # lire les secrets
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_project_iam_member" "api_pubsub_publisher" {
  project = var.project_id
  role    = "roles/pubsub.publisher"   # publier des messages Pub/Sub
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

# ③ Le pont KSA → GSA (Workload Identity)
# Dit : "le pod K8s kube-train-api-sa (namespace default) EST AUTORISÉ
#        à utiliser le badge GCP kube-train-api-sa@..."
resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  # ⚠️ namespace doit être EXACTEMENT celui du pod K8s
  # Une erreur ici → Connection reset silencieux au démarrage (SQLState 08001)
  member = "serviceAccount:${var.project_id}.svc.id.goog[default/kube-train-api-sa]"
}
```

**④ L'annotation K8s** (gérée par le CI, pas par Terraform) :
```bash
# Dans deploy.yml — dit au pod quel badge GCP utiliser
kubectl annotate serviceaccount kube-train-api-sa \
  iam.gke.io/gcp-service-account=kube-train-api-sa@kube-train-project.iam.gserviceaccount.com \
  --namespace=default --overwrite
```

---

**Différence `google_project_iam_member` vs `google_service_account_iam_member`** :

| Ressource | S'applique sur | Usage |
|---|---|---|
| `google_project_iam_member` | Le **projet GCP** entier | Donner des droits à un GSA sur les services (Cloud SQL, Pub/Sub...) |
| `google_service_account_iam_member` | Un **GSA spécifique** | Autoriser une identité à *utiliser* ce GSA (pont KSA→GSA) |

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

**Piège Pub/Sub** : créer le topic DLQ **avant** la subscription — sinon `terraform apply` échoue sur la référence à `dead_letter_topic`. Ici ce n'est pas un problème car Terraform résout l'ordre grâce aux références inter-ressources.

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
  # namespace "default" (pas "kube-train") — le pod tourne dans default sur kube-train
  member             = "serviceAccount:${var.project_id}.svc.id.goog[default/kube-train-api-sa]"
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

---

## 11. Erreurs et blocages rencontrés en TP — Retour d'expérience

> Cette section documente les vraies erreurs rencontrées lors du TP kube-train. Chaque incident correspond à un piège réel en production.

### 11.1 WIF namespace incorrect → `CrashLoopBackOff` / `Connection reset at doAuthentication`

**Symptôme** : Pod en `CrashLoopBackOff`, log `FATAL: connection reset`, SQLState `08001` (échec réseau, pas auth).

**Cause** : La ressource Terraform `google_service_account_iam_member` avait le mauvais namespace :
```hcl
# ❌ Mauvais — le pod tourne dans default, pas kube-train
member = "serviceAccount:kube-train-project.svc.id.goog[kube-train/kube-train-api-sa]"

# ✅ Correct
member = "serviceAccount:kube-train-project.svc.id.goog[default/kube-train-api-sa]"
```

**Règle** : Le namespace dans le membre WIF doit correspondre exactement au namespace K8s où tourne le pod. Une divergence entraîne un refus silencieux du token — le Cloud SQL Auth Proxy reçoit un token sans identité GCP valide → 403 → reset TCP.

**Fix immédiat sans terraform apply** :
```bash
kubectl annotate serviceaccount kube-train-api-sa \
  iam.gke.io/gcp-service-account=kube-train-api-sa@kube-train-project.iam.gserviceaccount.com \
  --namespace=default --overwrite
kubectl rollout restart deployment/kube-train-deployment
```

---

### 11.2 `github-actions-sa` sans `secretmanager.secretAccessor` → secrets vides silencieux

**Symptôme** : Pod crashe avec `FATAL: password authentication failed` (SQLState `28P01` = mauvais mot de passe). Le K8s secret `kube-train-secrets` existe mais `DB_PASSWORD` est une chaîne vide.

**Cause** : `github-actions-sa` n'avait pas `roles/secretmanager.secretAccessor`. La commande CI `gcloud secrets versions access latest --secret=db-password` retournait une chaîne vide **sans erreur**, puis écrasait le K8s secret avec la valeur vide.

**Fix permanent dans `iam.tf`** :
```hcl
resource "google_project_iam_member" "github_actions_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:github-actions-sa@${var.project_id}.iam.gserviceaccount.com"
}
```

**Distinction SQLState** :
- `08001` = échec connexion réseau/IAM → problème WIF ou réseau
- `28P01` = authentification échouée → mauvais mot de passe

---

### 11.3 OTel JAR corrompu → `Error opening zip file or JAR manifest missing`

**Symptôme** : Pod en `CrashLoopBackOff`, log `Error opening zip file or JAR manifest missing: /app/opentelemetry-javaagent.jar`.

**Cause** : Le `Dockerfile` utilisait `ADD https://github.com/...` pour télécharger l'agent OTel. Docker `ADD` ne suit pas les redirects multi-hop (GitHub → redirect → Azure Blob Storage) → fichier partiel/corrompu téléchargé silencieusement.

**Fix** :
```dockerfile
# ❌ Silencieux sur redirect multi-hop
ADD https://github.com/open-telemetry/...v2.26.1/opentelemetry-javaagent.jar /app/

# ✅ Échoue proprement au build si download raté
RUN curl -L --fail -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar
```

**Règle** : Toujours préférer `RUN curl --fail` à `ADD <URL>` pour les téléchargements depuis des CDN avec redirects.

---

### 11.4 JDBC + Cloud SQL Auth Proxy → `Connection reset at enableSSL`

**Symptôme** : Log `FATAL: Connection reset`, erreur à l'étape `enableSSL`.

**Cause** : Le driver JDBC PostgreSQL tentait une connexion SSL sur le proxy local (`127.0.0.1:5432`). Le Cloud SQL Auth Proxy **gère lui-même TLS** vers Cloud SQL et expose un socket TCP plain côté application — il ne supporte pas les négociations SSL entrantes.

**Fix dans `application-postgres.properties`** :
```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/kube_train?sslmode=disable
```

**Règle** : Avec Cloud SQL Auth Proxy v2, toujours ajouter `?sslmode=disable` dans l'URL JDBC. Le proxy gère le chiffrement de bout en bout côté GCP — pas besoin de SSL côté application.

---

### 11.5 `HikariCP` et proxy qui démarre en parallèle

**Symptôme** : Pod crashe au démarrage car HikariPool ne peut pas créer une connexion initiale (le proxy Cloud SQL n'est pas encore prêt).

**Fix dans `application-postgres.properties`** :
```properties
# -1 : le pool démarre avec 0 connexions, retente en arrière-plan
spring.datasource.hikari.initialization-fail-timeout=-1
spring.flyway.connect-retries=10
spring.flyway.connect-retries-interval=5
```

**Comportement** : Avec `-1`, HikariCP ne fait pas échouer le démarrage si la DB est indisponible. La première vraie connexion aura lieu quand Flyway tente la migration — et Flyway retente jusqu'à `connect-retries` fois.

---

### 11.6 `CrashLoopBackOff` → timeout `kubectl rollout status --timeout=5m`

**Symptôme** : CI échoue sur `kubectl rollout status deployment/kube-train-deployment --timeout=5m` avec `error: timed out waiting for the condition`.

**Cause** : Kubernetes applique un backoff exponentiel entre les redémarrages (10s → 20s → 40s → 80s → 160s → **5 min max**). Après plusieurs crashs successifs, K8s attend jusqu'à 5 minutes avant de redémarrer le pod — exactement le timeout CI.

**Règle** : En cas de `CrashLoopBackOff` sur un nouveau cluster, corriger d'abord la cause racine **avant** de relancer le CI. Le rollout status ne passera jamais si le pod crashe en boucle.

---

### 11.7 `terraform destroy` partiel — `google_sql_user` possède des objets

**Symptôme** : `terraform destroy` échoue sur :
```
role "kube_train_user" cannot be dropped because some objects depend on it
Details: 2 objects in database kube_train.
```

**Cause** : Hibernate (`ddl-auto=update`) crée des tables **avec `kube_train_user` comme owner**. PostgreSQL refuse de supprimer un rôle qui possède des objets. Terraform essaie de supprimer l'user avant l'instance → échec → l'instance Cloud SQL n'est **pas détruite**.

**Conséquences** : L'instance Cloud SQL continue de tourner et de facturer après `terraform destroy`.

**Fix** : Supprimer l'instance directement via gcloud :
```bash
gcloud sql instances delete kube-train-db --project=kube-train-project
```

**Alternative long terme** : En production, utiliser Flyway avec `ddl-auto=validate` (pas `update`) — les migrations sont versionnées et le rôle `kube_train_user` n'est pas owner des tables.

---

### 11.8 Restriction réutilisation du nom d'instance Cloud SQL après suppression

**Fait** : Après suppression d'une instance Cloud SQL, GCP bloque la réutilisation du **même nom** pendant ~7 jours.

**Impact** : Si `terraform destroy` supprime `kube-train-db` et qu'on recrée l'infra le lendemain, `terraform apply` échoue avec une erreur de nom déjà utilisé.

**Stratégie** : Préférer **stopper** l'instance plutôt que la détruire pour les cycles destroy/recreate fréquents (formation, weekend) :
```bash
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
# → 0 consommation de compute, facturation réduite (~0€/h hors stockage)
```

---

### 11.9 `github-actions-sa` sans `roles/container.admin` → RBAC forbidden

**Symptôme** : CI échoue avec `RBAC forbidden` lors du `kubectl apply -f k8s/rbac-gke.yaml`.

**Cause** : `github-actions-sa` avait `roles/container.developer` (deploy pods) mais pas `roles/container.admin` (créer Roles/RoleBindings).

**Fix dans `iam.tf`** :
```hcl
resource "google_project_iam_member" "github_actions_container_admin" {
  project = var.project_id
  role    = "roles/container.admin"
  member  = "serviceAccount:github-actions-sa@${var.project_id}.iam.gserviceaccount.com"
}
```

---

### 11.10 Git push rejeté — race condition avec les commits GitOps

**Symptôme** : `git push` rejeté avec `rejected: non-fast-forward`.

**Cause** : Le job CI `update-manifests` commit les image tags dans `deployment-gke.yaml` en parallèle des pushes locaux → conflit d'historique.

**Pattern correct** :
```bash
git pull --rebase origin main && git push origin main
```

**Règle** : Toujours `pull --rebase` avant de pousser sur main quand un CI/CD commit automatiquement des fichiers (GitOps image tags, changelogs, etc.).

---

## 12. Pour aller plus loin — Questions avancées

### 12.1 Le state Terraform peut-il être stocké en base de données (comme Flyway) ?

**Oui.** Terraform supporte plusieurs types de backends, dont PostgreSQL.

```hcl
# backend.tf — state stocké dans PostgreSQL
terraform {
  backend "pg" {
    conn_str = "postgres://user:password@host/dbname"
  }
}
```

Le backend `pg` crée une table `terraform_remote_state` et utilise les **advisory locks PostgreSQL** pour le verrouillage.

**Comparaison des backends les plus courants** :

| Backend | Locking | Versioning | Cas d'usage |
|---|---|---|---|
| `local` | Aucun | Non | Développement solo uniquement |
| `gcs` | Objet GCS (métadonnées) | Oui (versioning bucket) | Recommandé sur GCP |
| `pg` | Advisory locks PostgreSQL | Non natif | Si PostgreSQL déjà dans l'infra |
| `s3` | DynamoDB (AWS) | Oui (versioning S3) | Recommandé sur AWS |
| Terraform Cloud | Interne | Oui + chiffré | Équipes + state chiffré |

**Pourquoi GCS plutôt que PostgreSQL sur GCP** :
- GCS est managé — pas de serveur PostgreSQL à maintenir juste pour le state
- Le versioning GCS permet de restaurer un état corrompu sans configuration supplémentaire
- L'intégration IAM GCP est native (pas besoin de credentials DB séparées)
- Le locking GCS est fiable même en environnement distribué

**Analogie Flyway vs Terraform state** :

| | Flyway | Terraform state |
|---|---|---|
| Stocke | L'historique des migrations SQL (ce qui a été appliqué) | La correspondance HCL ↔ ressources réelles cloud |
| Format | Table `flyway_schema_history` | Fichier JSON (`terraform.tfstate`) |
| Verrou | `flyway_schema_history_lock` (SQL) | Backend-dépendant (GCS metadata, advisory lock...) |
| Source de vérité | Les fichiers de migration `.sql` | Les fichiers `.tf` |

---

### 12.2 Différence entre le lock GCS et un lock PostgreSQL

Les deux sont des **locks advisory** : Terraform les respecte, mais un processus externe peut les ignorer. `terraform force-unlock` permet de libérer manuellement un lock bloqué.

**Lock GCS** :
- Terraform écrit un objet `.terraform.tfstate.lock.info` dans le bucket
- Si un second `apply` démarre, il voit cet objet et refuse
- Si le process s'arrête brutalement (crash), le lock reste → nécessite `terraform force-unlock`
- Pas de timeout automatique (contrairement à certains backends DB)

```bash
# Libérer un lock GCS bloqué
terraform force-unlock <lock-id>
# L'ID est affiché dans le message d'erreur du lock
```

**Lock PostgreSQL (advisory lock)** :
- Utilise `pg_advisory_lock(id)` — un verrou de session PostgreSQL
- Si la connexion est coupée (crash), PostgreSQL **libère automatiquement** le lock
- Plus robuste sur les crashes, mais nécessite une connexion DB disponible
- Pas de versioning natif du state (sauf si le schéma PG est sauvegardé)

**Résumé** :

| Critère | GCS | PostgreSQL |
|---|---|---|
| Libération au crash | Non (manuel) | Oui (automatique) |
| Versioning du state | Oui (bucket versioning) | Non natif |
| Infrastructure requise | Bucket GCS (managé) | Instance PostgreSQL |
| Sur GCP | Recommandé | Faisable mais sur-complexe |

---

### 12.3 `terraform.tfstate` contient des infos sensibles — peut-on externaliser les mots de passe vers Secret Manager ?

**Le problème** : `sensitive = true` masque les valeurs dans les logs et le plan, mais **les valeurs sont quand même stockées en clair dans le state**. Le mot de passe Cloud SQL est lisible dans `terraform.tfstate`.

```json
// Dans terraform.tfstate (simplifié)
{
  "resource": "google_sql_user.app",
  "attributes": {
    "password": "mon-mdp-en-clair"   ← toujours présent
  }
}
```

**Approches pour limiter l'exposition** :

**① Chiffrement du bucket GCS** (recommandé sur GCP) :
```bash
# Activer CMEK (Customer Managed Encryption Key) sur le bucket state
gcloud storage buckets update gs://kube-train-terraform-state \
  --default-encryption-key=projects/.../cryptoKeyVersions/1
```
Le state est chiffré au repos avec une clé Cloud KMS que vous contrôlez.

**② Lire le secret depuis Secret Manager (pas le coder en variable)** :
```hcl
# Secret Manager comme source de vérité pour le mot de passe
data "google_secret_manager_secret_version" "db_password" {
  secret = "db-password"
}

resource "google_sql_user" "app" {
  name     = "kube_train_user"
  instance = google_sql_database_instance.main.name
  password = data.google_secret_manager_secret_version.db_password.secret_data
}
```
⚠️ La valeur sera quand même dans le state — l'avantage : Secret Manager est la source de vérité, le `.tfvars` n'est plus nécessaire.

**③ Terraform Cloud / HCP Terraform** : chiffre le state côté serveur, seuls les utilisateurs autorisés peuvent le lire.

**④ `writeOnly` (Terraform 1.11+)** : certains providers marquent les attributs sensibles avec `writeOnly = true` — ces valeurs ne sont **jamais** stockées dans le state. Terraform les passe à l'API mais ne les mémorise pas.

**Bonne pratique pour kube-train** :
1. Backend GCS avec versioning (déjà en place)
2. Accès au bucket limité par IAM (seul `github-actions-sa` et les admins)
3. Ne jamais committer `terraform.tfvars` (`.gitignore` en place)
4. Pour la production : CMEK sur le bucket + Terraform Cloud pour l'audit

---

### 12.4 Valeurs `null` dans `.terraform/terraform.tfstate` — que signifient-elles ?

Il existe **deux fichiers** `terraform.tfstate` à ne pas confondre :

```
infra/.terraform/terraform.tfstate   ← state LOCAL du backend (méta-config)
infra/terraform.tfstate              ← state de l'infra (n'existe pas si backend GCS actif)
```

**`.terraform/terraform.tfstate`** (le fichier avec des `null`) est le state **local du backend Terraform CLI**. Il enregistre quelle configuration de backend est active — pas l'infra elle-même.

```json
// .terraform/terraform.tfstate — exemple typique
{
  "version": 3,
  "serial": 1,
  "lineage": "abc-123",
  "backend": {
    "type": "gcs",
    "config": {
      "bucket": "kube-train-terraform-state",
      "prefix": "platform-engineering/dev",
      "credentials": null,      ← null = utilise les credentials par défaut (gcloud auth)
      "access_token": null,     ← null = non utilisé (on utilise les credentials ADC)
      "impersonate_service_account": null  ← null = pas d'impersonation configurée
    }
  }
}
```

**Les `null` signifient** : valeur non configurée explicitement → Terraform utilise la valeur par défaut ou les Application Default Credentials (ADC) de `gcloud`.

**Tu ne dois pas les modifier manuellement.** Ce fichier est géré automatiquement par `terraform init`. Si tu changes la config du backend dans `versions.tf`, relancer `terraform init -reconfigure` met à jour ce fichier.

**Quand ces valeurs sont-elles remplies ?**

| Champ | Rempli quand |
|---|---|
| `credentials` | Si tu passes explicitement un fichier JSON de SA |
| `access_token` | Si tu utilises un token OAuth2 temporaire |
| `impersonate_service_account` | Si tu configures l'impersonation de SA |

Sur GCP avec Workload Identity ou `gcloud auth application-default login`, ces champs restent `null` — c'est le comportement attendu.
