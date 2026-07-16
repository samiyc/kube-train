variable "project_id" {
  type    = string
  default = "kube-train-project"
}

variable "region" {
  type    = string
  default = "europe-west1"
}

# GSA applicatif EXISTANT, créé par infra/iam.tf. Le lab ne le possède pas :
# il ne fait que lui ajouter un rôle (binding additif), retiré au destroy.
variable "api_service_account" {
  type        = string
  description = "account_id du GSA de kube-train-api (Workload Identity)"
  default     = "kube-train-api-sa"
}
