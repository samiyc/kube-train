provider "google" {
  project = var.project_id
  region  = var.region
}

# Utilisé seulement par google_project_service_identity (cf. main.tf).
provider "google-beta" {
  project = var.project_id
  region  = var.region
}
