variable "project_id" {
  type    = string
  default = "kube-train-project"
}

variable "region" {
  type    = string
  default = "europe-west1"
}

# Seul ce dépôt pourra échanger un token OIDC GitHub contre un token GCP.
variable "github_repo" {
  type        = string
  description = "Dépôt GitHub autorisé (owner/repo)"
  default     = "samiyc/kube-train"
}

# ...et seulement depuis cette branche (bloque les PR et les branches de feature).
variable "github_branch" {
  type        = string
  description = "Branche autorisée à déployer"
  default     = "main"
}

# Secrets gérés en tant que CONTENEURS uniquement — les valeurs restent hors state.
variable "app_secrets" {
  type        = list(string)
  description = "Noms des secrets Secret Manager consommés par la CI"
  default     = ["api-key", "db-username", "db-password"]
}
