variable "project_id" {
  type    = string
  default = "kube-train-project"
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "cluster_name" {
  type        = string
  description = "Cluster GKE existant (créé par infra/) — lu en data source, jamais géré ici"
  default     = "kube-train-cluster"
}

# Préfixe commun à toutes les ressources du lab → repérables et supprimables d'un coup.
variable "lab_prefix" {
  type    = string
  default = "lab-clouddeploy"
}
