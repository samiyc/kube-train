terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
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
