# APIs propres au lab (le socle reste dans infra/apis.tf).
# disable_on_destroy = false : détruire le lab ne désactive pas l'API (inoffensif, gratuit,
# et évite de casser autre chose qui l'utiliserait).

resource "google_project_service" "clouddeploy" {
  project            = var.project_id
  service            = "clouddeploy.googleapis.com"
  disable_on_destroy = false
}

# Cloud Deploy n'exécute rien lui-même : il délègue le rendu (skaffold) et le déploiement
# à des jobs Cloud Build. Sans cette API, les rollouts échouent.
resource "google_project_service" "cloudbuild" {
  project            = var.project_id
  service            = "cloudbuild.googleapis.com"
  disable_on_destroy = false
}
