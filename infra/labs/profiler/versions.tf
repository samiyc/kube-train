terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # State JETABLE, isolé de infra/ et de bootstrap/.
  backend "gcs" {
    bucket = "kube-train-terraform-state"
    prefix = "platform-engineering/labs/profiler"
  }
}
