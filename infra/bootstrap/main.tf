# ═══════════════════════════════════════════════════════════════════════════════
#  BOOTSTRAP — couche identité & secrets
#
#  ⛔ NE JAMAIS exécuter `terraform destroy` dans ce répertoire.
#
#  Ces ressources ont un cycle de vie « une fois par projet ». Elles sont dans un
#  state séparé (prefix platform-engineering/bootstrap) pour qu'un destroy dans
#  infra/ — qui est fait quotidiennement pour le budget — ne puisse pas les
#  supprimer.
#
#  Pourquoi c'est critique :
#   - le pool Workload Identity est en SOFT-DELETE 30 JOURS : une fois supprimé,
#     impossible de recréer un pool du même nom pendant un mois → la CI ne peut
#     plus s'authentifier auprès de GCP ;
#   - supprimer les secrets détruirait leurs valeurs (api-key, db-password).
#
#  Les ressources les plus irrécupérables portent `prevent_destroy = true` :
#  toute tentative de destroy échouera bruyamment au lieu de nuire.
# ═══════════════════════════════════════════════════════════════════════════════

data "google_project" "this" {
  project_id = var.project_id
}

# ── Service account de la CI GitHub Actions ───────────────────────────────────
resource "google_service_account" "github_actions" {
  account_id   = "github-actions-sa"
  display_name = "GSA pour la CI GitHub Actions"
}

# Rôles nécessaires au pipeline :
#  - artifactregistry.writer : `docker push` (job build, tourne à CHAQUE push)
#  - container.admin         : `kubectl apply` sur GKE (job deploy)
#  - secretmanager.secretAccessor : lecture des secrets pour créer le secret K8s
#
# Ces bindings vivent ici (et non dans infra/iam.tf) pour survivre au destroy :
# sans artifactregistry.writer, le job build échouerait même sans cluster.
resource "google_project_iam_member" "github_actions" {
  for_each = toset([
    "roles/artifactregistry.writer",
    "roles/container.admin",
    "roles/secretmanager.secretAccessor",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# ── Workload Identity Federation : GitHub Actions sans clé JSON ───────────────
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github-pool"
  display_name              = "GitHub Actions Pool" # aligné sur l'existant (pas de description)

  lifecycle {
    prevent_destroy = true # soft-delete 30 jours — voir en-tête
  }
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-provider"
  display_name                       = "GitHub Provider"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
  }

  # Double verrou : seul le dépôt déclaré ET la branche déclarée peuvent échanger
  # un token OIDC contre un token GCP. Retirer la condition sur `ref` permettrait
  # à n'importe quelle branche (donc à n'importe quelle PR) de déployer.
  attribute_condition = "assertion.repository=='${var.github_repo}' && assertion.ref=='refs/heads/${var.github_branch}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Autorise UNIQUEMENT le dépôt var.github_repo à usurper github-actions-sa.
resource "google_service_account_iam_member" "github_wif" {
  service_account_id = google_service_account.github_actions.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.this.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/attribute.repository/${var.github_repo}"
}

# ── Secret Manager : conteneurs uniquement, jamais les valeurs ────────────────
# Mettre une valeur (hors Terraform, donc hors state) :
#   printf '%s' '<valeur>' | gcloud secrets versions add api-key \
#     --data-file=- --project=kube-train-project
resource "google_secret_manager_secret" "app" {
  for_each  = toset(var.app_secrets)
  secret_id = each.value

  replication {
    auto {}
  }

  lifecycle {
    prevent_destroy = true # détruire le secret détruit toutes ses versions
  }
}
