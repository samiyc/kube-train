# ═══════════════════════════════════════════════════════════════════════════════
#  LAB Phase 0 — Cloud Deploy : delivery pipeline managé (staging → prod)
#
#  Ce que Terraform fait ici : PROVISIONNER le pipeline (le "tapis roulant").
#  Ce que Terraform ne fait PAS : créer des releases / promouvoir / rollback.
#  Ça, c'est de l'EXPLOITATION → `gcloud deploy ...` (voir la note du lab).
#
#  Isolation : state jetable (versions.tf) + workloads dans des namespaces dédiés
#  (lab-cloud-deploy-staging / -prod) créés par les manifests, pas ici.
# ═══════════════════════════════════════════════════════════════════════════════

# ── SA d'exécution ────────────────────────────────────────────────────────────
# Cloud Deploy ne s'exécute pas "en tant que toi" : chaque job (render/deploy) tourne
# avec cette identité. C'est elle qui doit avoir le droit de parler au cluster.
resource "google_service_account" "deploy" {
  account_id   = "${var.lab_prefix}-sa"
  display_name = "SA d'exécution Cloud Deploy (lab Phase 0)"
}

resource "google_project_iam_member" "deploy_sa" {
  for_each = toset([
    "roles/clouddeploy.jobRunner",       # exécuter les jobs render/deploy
    "roles/container.developer",         # appliquer les manifests sur GKE
    "roles/logging.logWriter",           # écrire les logs des jobs Cloud Build
    "roles/storage.objectUser",          # bucket d'artefacts de rendu (auto-créé par Cloud Deploy)
    "roles/artifactregistry.reader",     # tirer des images (si image privée)
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.deploy.email}"
}

# Le SERVICE AGENT de Cloud Deploy doit pouvoir « endosser » le SA d'exécution ci-dessus.
# Sans ce binding : "failed to impersonate service account" au premier rollout.
# L'agent est créé automatiquement à l'activation de l'API → d'où le depends_on.
resource "google_service_account_iam_member" "agent_impersonates_exec_sa" {
  service_account_id = google_service_account.deploy.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-clouddeploy.iam.gserviceaccount.com"

  depends_on = [google_project_service.clouddeploy]
}

# ── Targets = les destinations ────────────────────────────────────────────────
# Un target = « où » déployer. Ici les DEUX pointent sur le MÊME cluster GKE mais des
# namespaces différents (choix budget : un seul cluster). En vrai on aurait 2 clusters —
# le concept de promotion est identique.

resource "google_clouddeploy_target" "staging" {
  name     = "${var.lab_prefix}-staging"
  location = var.region

  gke {
    cluster = data.google_container_cluster.main.id
  }

  execution_configs {
    usages          = ["RENDER", "DEPLOY"]
    service_account = google_service_account.deploy.email
  }

  depends_on = [google_project_service.clouddeploy]
}

resource "google_clouddeploy_target" "prod" {
  name     = "${var.lab_prefix}-prod"
  location = var.region

  # 🎯 LE point d'apprentissage : une porte d'approbation humaine avant la prod.
  # La promotion vers ce target crée un rollout en PENDING_APPROVAL tant que
  # `gcloud deploy rollouts approve` n'est pas lancé.
  require_approval = true

  gke {
    cluster = data.google_container_cluster.main.id
  }

  execution_configs {
    usages          = ["RENDER", "DEPLOY"]
    service_account = google_service_account.deploy.email
  }

  depends_on = [google_project_service.clouddeploy]
}

# ── Le pipeline = l'ordre des étapes ──────────────────────────────────────────
# serial_pipeline : les stages s'enchaînent DANS L'ORDRE. On ne peut pas sauter
# staging pour aller en prod — c'est toute la garantie du modèle.
resource "google_clouddeploy_delivery_pipeline" "pipeline" {
  name     = "${var.lab_prefix}-pipeline"
  location = var.region

  serial_pipeline {
    stages {
      target_id = google_clouddeploy_target.staging.name
      profiles  = ["staging"] # → profil skaffold correspondant
    }
    stages {
      target_id = google_clouddeploy_target.prod.name
      profiles  = ["prod"]
    }
  }

  depends_on = [google_project_service.clouddeploy]
}

# ── Outputs : ce dont tu auras besoin pour l'exploitation ────────────────────
output "pipeline_name" {
  value = google_clouddeploy_delivery_pipeline.pipeline.name
}

output "region" {
  value = var.region
}

output "console_url" {
  description = "Vue du pipeline dans la console (le tapis roulant en image)"
  value       = "https://console.cloud.google.com/deploy/delivery-pipelines/${var.region}/${google_clouddeploy_delivery_pipeline.pipeline.name}?project=${var.project_id}"
}
