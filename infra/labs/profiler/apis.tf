# API propre au lab (le socle reste dans infra/apis.tf).
# disable_on_destroy = false : détruire le lab ne désactive pas l'API.
resource "google_project_service" "cloudprofiler" {
  project            = var.project_id
  service            = "cloudprofiler.googleapis.com"
  disable_on_destroy = false
}
