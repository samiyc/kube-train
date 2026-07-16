terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    # Requis uniquement pour google_project_service_identity (ressource beta-only)
    # qui force la création du service agent Cloud Deploy. Voir main.tf.
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 6.0"
    }
  }

  # State JETABLE, isolé de infra/ et de bootstrap/.
  # `terraform destroy` ici ne peut rien casser d'autre que ce lab.
  backend "gcs" {
    bucket = "kube-train-terraform-state"
    prefix = "platform-engineering/labs/cloud-deploy"
  }
}
