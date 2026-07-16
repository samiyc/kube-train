# ═══════════════════════════════════════════════════════════════════════════════
#  LAB Phase 0 — Cloud Profiler : le diagnostic moteur, branché en permanence
#
#  Particularité vs le lab Cloud Deploy : Profiler exige un agent IN-PROCESS.
#  Impossible de profiler l'app depuis l'extérieur → ce lab touche aussi l'app
#  (Dockerfile + deployment). Isolation à deux niveaux :
#    - infra  → ce state jetable (API + binding IAM)
#    - app    → git (revert du commit après le lab)
#
#  Ce que Terraform NE fait PAS : installer l'agent. Voir kube-train-api/Dockerfile
#  et k8s/workloads/deployment-gke.yaml (JAVA_TOOL_OPTIONS).
# ═══════════════════════════════════════════════════════════════════════════════

# Le GSA de kube-train-api est créé par infra/iam.tf → lu, jamais géré ici.
data "google_service_account" "api" {
  account_id = var.api_service_account
}

# L'agent Profiler s'authentifie via Workload Identity (ADC, metadata server GKE) —
# exactement comme le Cloud SQL Proxy et le client Pub/Sub. Il lui faut ce rôle pour
# pousser les profils.
#
# 🎯 google_project_iam_member est ADDITIF : le lab ajoute un rôle à un SA qu'il ne
# possède pas, et le destroy retire UNIQUEMENT ce rôle. Le SA et ses autres rôles
# (cloudsql.client, pubsub.publisher…) appartiennent à infra/ et n'y sont pas touchés.
resource "google_project_iam_member" "profiler_agent" {
  project = var.project_id
  role    = "roles/cloudprofiler.agent"
  member  = "serviceAccount:${data.google_service_account.api.email}"

  depends_on = [google_project_service.cloudprofiler]
}

output "profiled_service_account" {
  value = data.google_service_account.api.email
}

output "console_url" {
  description = "Le flame graph de kube-train-api"
  value       = "https://console.cloud.google.com/profiler/kube-train-api/cpu?project=${var.project_id}"
}
