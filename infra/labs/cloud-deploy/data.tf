# Lecture de l'existant PAR SON NOM (règle labs : jamais de terraform_remote_state).
# Couplage lâche : si infra/ est détruit puis recréé, ce lab n'a rien à savoir — il relit
# simplement le cluster portant ce nom.

data "google_project" "this" {
  project_id = var.project_id
}

data "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region
}
