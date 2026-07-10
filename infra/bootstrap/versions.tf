terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # State SÉPARÉ de infra/ (prefix distinct) : c'est ce qui garantit qu'un
  # `terraform destroy` dans infra/ ne peut pas toucher à ces ressources.
  backend "gcs" {
    bucket = "kube-train-terraform-state"
    prefix = "platform-engineering/bootstrap"
  }
}
