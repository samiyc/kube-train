# APIs GCP requises par le projet.
#
# disable_on_destroy = false : un `terraform destroy` ne désactive JAMAIS les APIs.
# Sans ça, chaque cycle destroy/apply casserait les services qui en dépendent, et
# la réactivation d'une API peut prendre plusieurs minutes.
#
# Sur le projet actuel ces APIs sont déjà activées → l'apply est idempotent
# (activer une API déjà active est un no-op). Sur un projet NEUF, ce fichier est
# ce qui rend `terraform apply` reproductible depuis zéro : sans lui, l'apply
# échoue dès la première ressource.

locals {
  required_apis = [
    # ── Socle projet / IAM ──
    "cloudresourcemanager.googleapis.com", # bindings IAM au niveau projet
    "serviceusage.googleapis.com",         # activation des APIs elles-mêmes
    "compute.googleapis.com",              # VPC + subnet (data sources), LoadBalancers
    "iam.googleapis.com",                  # service accounts
    "iamcredentials.googleapis.com",       # impersonation / Workload Identity
    "sts.googleapis.com",                  # échange de token WIF (GitHub Actions)

    # ── Workloads ──
    "container.googleapis.com",        # GKE
    "sqladmin.googleapis.com",         # Cloud SQL
    "artifactregistry.googleapis.com", # images Docker
    "pubsub.googleapis.com",           # messaging
    "secretmanager.googleapis.com",    # secrets api-key / db-*

    # ── Observabilité ──
    "cloudtrace.googleapis.com", # export des traces OTel
    "monitoring.googleapis.com", # métriques + SLOs
    "logging.googleapis.com",    # Cloud Logging

    # ── Cloud Service Mesh (Istio managé) ──
    "trafficdirector.googleapis.com",
    "meshca.googleapis.com",
    "networksecurity.googleapis.com",
    "networkservices.googleapis.com",
  ]
}

resource "google_project_service" "required" {
  for_each = toset(local.required_apis)

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# ── Migration des 4 ressources historiques vers la map for_each ───────────────
# `moved` réécrit l'adresse dans le state sans aucun appel GCP : le plan affiche
# 0 destroy / 0 create pour ces 4 APIs.
moved {
  from = google_project_service.trafficdirector
  to   = google_project_service.required["trafficdirector.googleapis.com"]
}

moved {
  from = google_project_service.meshca
  to   = google_project_service.required["meshca.googleapis.com"]
}

moved {
  from = google_project_service.networksecurity
  to   = google_project_service.required["networksecurity.googleapis.com"]
}

moved {
  from = google_project_service.networkservices
  to   = google_project_service.required["networkservices.googleapis.com"]
}
