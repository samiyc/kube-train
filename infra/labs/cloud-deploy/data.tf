# Lecture de l'existant PAR SON NOM (règle labs : jamais de terraform_remote_state).
# Couplage lâche : si infra/ est détruit puis recréé, ce lab n'a rien à savoir — il relit
# simplement le cluster portant ce nom.
#
# Note : plus besoin de data.google_project — l'email du service agent Cloud Deploy vient
# désormais de google_project_service_identity (main.tf), pas d'une chaîne construite à la
# main depuis le numéro de projet.

data "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region
}
