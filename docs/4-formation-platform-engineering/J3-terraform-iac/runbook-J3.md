# Runbook F4-J3 — Terraform IaC

> Extrait du runbook F4. Prérequis communs & debug multi-containers : voir [`../runbook.md`](../runbook.md).

```bash
# ── Bootstrap (une seule fois) ────────────────────────────────────────────────
# Créer le bucket GCS pour le state Terraform
gcloud storage buckets create gs://kube-train-terraform-state \
  --project=kube-train-project \
  --location=europe-west1 \
  --uniform-bucket-level-access

gcloud storage buckets update gs://kube-train-terraform-state --versioning

# ── Workflow quotidien ────────────────────────────────────────────────────────
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra

terraform init                   # initialise provider + backend GCS
terraform fmt -recursive         # reformate les fichiers .tf
terraform validate               # vérifie la syntaxe sans appel réseau
terraform plan                   # diff state ↔ infra ↔ config HCL
terraform apply                  # applique le plan (demande confirmation)
terraform apply -auto-approve    # applique sans confirmation (CI/CD)
terraform destroy                # supprime toutes les ressources (fin de journée)

# ── Import de ressources existantes ──────────────────────────────────────────
# GKE cluster
terraform import \
  google_container_cluster.main \
  projects/kube-train-project/locations/europe-west1/clusters/kube-train-cluster

# Cloud SQL instance
terraform import \
  google_sql_database_instance.main \
  projects/kube-train-project/instances/kube-train-db

# Artifact Registry
terraform import \
  google_artifact_registry_repository.docker \
  projects/kube-train-project/locations/europe-west1/repositories/kube-train-repo

# Inspecter une ressource importée
terraform state show google_container_cluster.main
terraform state list             # liste toutes les ressources dans le state

# ── Vérifications post-apply ─────────────────────────────────────────────────
terraform output                 # affiche les outputs définis

gcloud container clusters list --project=kube-train-project
gcloud sql instances describe kube-train-db --project=kube-train-project
gcloud artifacts repositories list --location=europe-west1 --project=kube-train-project
gcloud pubsub topics list --project=kube-train-project
gcloud iam service-accounts list --project=kube-train-project

# ── Économies GCP ─────────────────────────────────────────────────────────────
# Stopper Cloud SQL sans détruire l'infra
gcloud sql instances patch kube-train-db \
  --activation-policy=NEVER \
  --project=kube-train-project

# Redémarrer Cloud SQL le lendemain
gcloud sql instances patch kube-train-db \
  --activation-policy=ALWAYS \
  --project=kube-train-project

# Pipeline GitHub Actions Terraform
gh run list --workflow terraform-infra
gh run watch                     # suivre le run en temps réel
```
